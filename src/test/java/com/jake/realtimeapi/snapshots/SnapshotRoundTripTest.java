package com.jake.realtimeapi.snapshots;

import com.jake.realtimeapi.TestcontainersConfiguration;
import com.jake.realtimeapi.leaderboards.domain.model.Leaderboard;
import com.jake.realtimeapi.leaderboards.domain.repository.LeaderboardRepository;
import com.jake.realtimeapi.projects.domain.model.Project;
import com.jake.realtimeapi.projects.domain.repository.ProjectRepository;
import com.jake.realtimeapi.snapshots.application.command.CaptureSnapshotCommand;
import com.jake.realtimeapi.snapshots.application.command.RecoverLeaderboardSnapshotCommand;
import com.jake.realtimeapi.snapshots.application.model.RecoverLeaderboardSnapshotResult;
import com.jake.realtimeapi.snapshots.application.usecase.CaptureSnapshotUseCase;
import com.jake.realtimeapi.snapshots.application.usecase.RecoverLeaderboardSnapshotUseCase;
import com.jake.realtimeapi.support.redis.LeaderboardRedisKeyFactory;
import com.jake.realtimeapi.support.userid.UserIdCodec;
import com.jake.realtimeapi.users.domain.model.User;
import com.jake.realtimeapi.users.domain.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Snapshot capture→recover 왕복 통합 테스트 (Testcontainers, 실제 Redis + PostgreSQL).
 *
 * <p>Redis ZSET 상태를 PostgreSQL snapshot으로 capture → 랭킹 키를 비움 → snapshot으로 recover 했을 때
 * 유저별 점수가 그대로 왕복하는지 검증한다.
 *
 * <p>테스트 설계:
 * <ul>
 *   <li>판정은 score만 비교한다. rank는 recover 시 score로부터 재계산되는 파생값이므로 왕복 대상이 아니다.
 *   <li>capture는 유스케이스를 직접 호출한다 (스냅샷 실행 advisory lock은 이 테스트의 관심사가 아니다).
 *   <li>Redis는 대상 랭킹 키만 삭제해 공유 컨테이너의 다른 테스트와 격리한다 (@DirtiesContext 불필요).
 *   <li>recover도 유스케이스를 직접 호출한다 (cold-start 러너 오케스트레이션과 분리해 왕복만 검증).
 * </ul>
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SnapshotRoundTripTest {

    private static final int TOP_N = 1000;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private LeaderboardRepository leaderboardRepository;

    @Autowired
    private CaptureSnapshotUseCase captureSnapshotUseCase;

    @Autowired
    private RecoverLeaderboardSnapshotUseCase recoverSnapshotUseCase;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    void capture_thenRecover_restoresSameScoresPerUser() {
        // capture는 snapshot_batches / snapshot_entries에 쓰므로 FK 부모 행이 존재해야 한다:
        // snapshot_entries.user_id → users, snapshot_batches.leaderboard_id → leaderboards → projects.
        // external_id는 VARCHAR(30) 제한이라 짧은 토큰으로 유니크를 만든다.
        String runToken = UUID.randomUUID().toString().substring(0, 8);
        List<User> users = IntStream.range(0, 3)
                .mapToObj(i -> userRepository.save(User.newUser("snap-" + runToken + "-" + i)))
                .toList();
        Project project = projectRepository.save(Project.newProject("snap-proj-" + UUID.randomUUID(), users.get(0).id()));
        Leaderboard leaderboard = leaderboardRepository.save(Leaderboard.newLeaderboard(project.id(), "snap-board"));
        UUID leaderboardId = leaderboard.id();
        String rankingKey = LeaderboardRedisKeyFactory.rankingKey(leaderboardId);

        // capture 대상 상태를 정수 점수로 시딩한다 (long↔double 변환이 정확). member는 유저 PK 문자열.
        Map<Long, Long> seededScores = new LinkedHashMap<>();
        long score = 300L;
        for (User user : users) {
            redisTemplate.opsForZSet().add(rankingKey, UserIdCodec.format(user.id()), (double) score);
            seededScores.put(user.id(), score);
            score -= 100L;
        }

        captureSnapshotUseCase.capture(new CaptureSnapshotCommand(leaderboardId, Instant.now(), TOP_N));

        // 랭킹 키만 비운다: recover의 REDIS_ALREADY_WARM 가드를 통과시키면서 다른 테스트와 격리한다.
        redisTemplate.delete(rankingKey);

        RecoverLeaderboardSnapshotResult recoverResult =
                recoverSnapshotUseCase.recover(new RecoverLeaderboardSnapshotCommand(leaderboardId, TOP_N));

        // recover가 실제로 복구했는지 먼저 확정한다. skip(REDIS_ALREADY_WARM)이면
        // 아래 score 비교가 삭제 전 값과 대조되어 거짓 통과할 수 있다.
        assertTrue(recoverResult.recovered());
        assertEquals("RECOVERED", recoverResult.reason());
        assertEquals(3, recoverResult.restoredRowCount());

        // 유저별 score 왕복 검증. ZSCORE는 Double, seed는 Long이므로 double로 맞춰 숫자 비교한다.
        for (Map.Entry<Long, Long> entry : seededScores.entrySet()) {
            Double restored = redisTemplate.opsForZSet().score(rankingKey, UserIdCodec.format(entry.getKey()));
            assertEquals(entry.getValue().doubleValue(), restored, 0.0);
        }

        // topN 범위 내 멤버 수 일치 (유실/추가 없음).
        assertEquals(seededScores.size(), redisTemplate.opsForZSet().zCard(rankingKey).intValue());
    }
}
