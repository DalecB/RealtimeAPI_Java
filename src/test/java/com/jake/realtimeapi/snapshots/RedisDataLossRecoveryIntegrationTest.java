package com.jake.realtimeapi.snapshots;

import com.jake.realtimeapi.TestcontainersConfiguration;
import com.jake.realtimeapi.events.application.query.GetTopRanksQuery;
import com.jake.realtimeapi.events.application.usecase.GetTopRanksUseCase;
import com.jake.realtimeapi.events.domain.model.EventPayload;
import com.jake.realtimeapi.events.domain.model.RankingSource;
import com.jake.realtimeapi.events.domain.model.TopRanksResult;
import com.jake.realtimeapi.events.domain.repository.EventCommandRepository;
import com.jake.realtimeapi.events.domain.repository.RankingWarmthRepository;
import com.jake.realtimeapi.infra.circuitbreaker.RankingRebuildingException;
import com.jake.realtimeapi.leaderboards.domain.model.Leaderboard;
import com.jake.realtimeapi.leaderboards.domain.repository.LeaderboardRepository;
import com.jake.realtimeapi.projects.domain.model.Project;
import com.jake.realtimeapi.projects.domain.repository.ProjectRepository;
import com.jake.realtimeapi.snapshots.worker.SnapshotCaptureExecutionResult;
import com.jake.realtimeapi.snapshots.worker.SnapshotCaptureExecutionService;
import com.jake.realtimeapi.events.persistence.redis.LeaderboardRedisKeyFactory;
import com.jake.realtimeapi.support.userid.UserIdCodec;
import com.jake.realtimeapi.users.domain.model.User;
import com.jake.realtimeapi.users.domain.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 앱이 떠 있는 동안 Redis가 랭킹을 잃는 경우(Redis + AOF 유실, FLUSHALL)를 실제 컨테이너로 검증한다.
 * 이 리더보드의 랭킹·warm 키만 지워 유실을 흉내 낸다(공유 컨테이너의 다른 테스트와 격리).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RedisDataLossRecoveryIntegrationTest {

    private static final int TOP_N = 1000;
    private static final long API_KEY_ID = 1L;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private LeaderboardRepository leaderboardRepository;

    @Autowired
    private RankingWarmthRepository rankingWarmthRepository;

    @Autowired
    private EventCommandRepository eventCommandRepository;

    @Autowired
    private GetTopRanksUseCase getTopRanksUseCase;

    @Autowired
    private SnapshotCaptureExecutionService snapshotCaptureExecutionService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    void lostRanking_isServedFromSnapshot_rejectsWrites_andIsRebuiltByTheNextWorkerRun() {
        Seeded seeded = seedLeaderboardWithSnapshot();
        GetTopRanksQuery query = new GetTopRanksQuery(seeded.leaderboardId(), 0, 10);

        loseRedisData(seeded.leaderboardId());

        // 빈 랭킹을 정상 응답으로 내보내지 않고, 스냅샷 시점의 같은 순위로 답한다
        TopRanksResult duringLoss = getTopRanksUseCase.getTopRanks(query);
        assertEquals(RankingSource.SNAPSHOT, duringLoss.source());
        assertEquals(3L, duringLoss.total());
        // 빈 랭킹에 점수가 쌓이면 복구가 막히므로 쓰기는 거부한다
        assertThrows(RankingRebuildingException.class, () -> eventCommandRepository.process(
                new EventPayload(seeded.leaderboardId(), seeded.userIds().get(0), 5L, UUID.randomUUID()), API_KEY_ID));

        // 앱 재시작 없이 다음 스냅샷 주기가 되살린다
        SnapshotCaptureExecutionResult run = snapshotCaptureExecutionService.execute(seeded.leaderboardId(), Instant.now(), TOP_N);
        assertTrue(run.recoveryResult().recovered());

        TopRanksResult afterRebuild = getTopRanksUseCase.getTopRanks(query);
        assertEquals(RankingSource.REDIS, afterRebuild.source());
        assertEquals(List.of(300L, 200L, 100L), afterRebuild.items().stream().map(item -> item.score()).toList());
        eventCommandRepository.process(
                new EventPayload(seeded.leaderboardId(), seeded.userIds().get(2), 5L, UUID.randomUUID()), API_KEY_ID);
    }

    @Test
    void partialRanking_neverOverwritesTheSnapshot() {
        // warm 키가 생기기 전 코드에서 실제로 일어난 일: 유실 직후 점수 1건이 들어와 랭킹에 한 명만 남았다
        Seeded seeded = seedLeaderboardWithSnapshot();
        loseRedisData(seeded.leaderboardId());
        rankingWarmthRepository.markWarm(seeded.leaderboardId());
        redisTemplate.opsForZSet().add(LeaderboardRedisKeyFactory.rankingKey(seeded.leaderboardId()),
                UserIdCodec.format(seeded.userIds().get(0)), 5);

        SnapshotCaptureExecutionResult run = snapshotCaptureExecutionService.execute(seeded.leaderboardId(), Instant.now(), TOP_N);

        // Shrink Guard: 참여자는 줄지 않으므로 3명 → 1명 캡처는 거부한다. 스냅샷의 세 명은 그대로 남는다.
        assertTrue(run.captureResult().skipped());
        assertEquals("SHRUNK", run.captureResult().skipReason());
        loseRedisData(seeded.leaderboardId());
        assertEquals(3L, getTopRanksUseCase.getTopRanks(new GetTopRanksQuery(seeded.leaderboardId(), 0, 10)).total());
    }

    private Seeded seedLeaderboardWithSnapshot() {
        String token = UUID.randomUUID().toString().substring(0, 8);
        List<Long> userIds = IntStream.range(0, 3)
                .mapToObj(i -> userRepository.save(User.newUser("loss-" + token + "-" + i)).id())
                .toList();
        Project project = projectRepository.save(Project.newProject("loss-proj-" + UUID.randomUUID(), userIds.get(0)));
        UUID leaderboardId = leaderboardRepository.save(Leaderboard.newLeaderboard(project.id(), "loss-board")).id();
        rankingWarmthRepository.markWarm(leaderboardId);
        String rankingKey = LeaderboardRedisKeyFactory.rankingKey(leaderboardId);
        long score = 300L;
        for (long userId : userIds) {
            redisTemplate.opsForZSet().add(rankingKey, UserIdCodec.format(userId), score);
            score -= 100L;
        }
        SnapshotCaptureExecutionResult captured = snapshotCaptureExecutionService.execute(leaderboardId, Instant.now(), TOP_N);
        assertEquals(3, captured.captureResult().rowCount());
        return new Seeded(leaderboardId, userIds);
    }

    private void loseRedisData(UUID leaderboardId) {
        redisTemplate.delete(List.of(
                LeaderboardRedisKeyFactory.rankingKey(leaderboardId),
                LeaderboardRedisKeyFactory.warmKey(leaderboardId)));
    }

    private record Seeded(UUID leaderboardId, List<Long> userIds) {
    }
}
