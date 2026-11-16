package com.jake.realtimeapi.snapshots;

import com.jake.realtimeapi.TestcontainersConfiguration;
import com.jake.realtimeapi.events.persistence.redis.LeaderboardRedisKeyFactory;
import com.jake.realtimeapi.leaderboards.domain.model.Leaderboard;
import com.jake.realtimeapi.leaderboards.domain.repository.LeaderboardRepository;
import com.jake.realtimeapi.projects.domain.model.Project;
import com.jake.realtimeapi.projects.domain.repository.ProjectRepository;
import com.jake.realtimeapi.snapshots.application.command.CaptureSnapshotCommand;
import com.jake.realtimeapi.snapshots.application.command.RecoverLeaderboardSnapshotCommand;
import com.jake.realtimeapi.snapshots.application.model.RecoverLeaderboardSnapshotResult;
import com.jake.realtimeapi.snapshots.application.usecase.CaptureSnapshotUseCase;
import com.jake.realtimeapi.snapshots.application.usecase.RecoverLeaderboardSnapshotUseCase;
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

// rank는 Redis에 없고 score에서 다시 계산되는 파생값이라 score만 비교한다.
// capture·recover는 유스케이스를 직접 부른다. advisory lock과 cold-start 러너는 이 테스트 관심사가 아니다.
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
        // snapshot 테이블 FK 때문에 users·projects·leaderboards 부모 행이 필요하고, external_id는 VARCHAR(30)이라 짧은 토큰을 쓴다.
        String runToken = UUID.randomUUID().toString().substring(0, 8);
        List<User> users = IntStream.range(0, 3)
                .mapToObj(i -> userRepository.save(User.newUser("snap-" + runToken + "-" + i)))
                .toList();
        Project project = projectRepository.save(Project.newProject("snap-proj-" + UUID.randomUUID(), users.get(0).id()));
        Leaderboard leaderboard = leaderboardRepository.save(Leaderboard.newLeaderboard(project.id(), "snap-board"));
        UUID leaderboardId = leaderboard.id();
        String rankingKey = LeaderboardRedisKeyFactory.rankingKey(leaderboardId);

        // capture가 정수 score만 받으므로 long↔double 변환이 정확한 정수로 시딩한다.
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

        assertTrue(recoverResult.recovered());
        assertEquals("RECOVERED", recoverResult.reason());
        assertEquals(3, recoverResult.restoredRowCount());

        for (Map.Entry<Long, Long> entry : seededScores.entrySet()) {
            Double restored = redisTemplate.opsForZSet().score(rankingKey, UserIdCodec.format(entry.getKey()));
            assertEquals(entry.getValue().doubleValue(), restored, 0.0);
        }

        assertEquals(seededScores.size(), redisTemplate.opsForZSet().zCard(rankingKey).intValue());
    }
}
