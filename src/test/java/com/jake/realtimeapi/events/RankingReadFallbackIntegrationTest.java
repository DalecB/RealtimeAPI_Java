package com.jake.realtimeapi.events;

import com.jake.realtimeapi.TestcontainersConfiguration;
import com.jake.realtimeapi.events.application.query.GetTopRanksQuery;
import com.jake.realtimeapi.events.application.usecase.GetTopRanksUseCase;
import com.jake.realtimeapi.events.domain.model.RankingSource;
import com.jake.realtimeapi.events.domain.model.TopRankItem;
import com.jake.realtimeapi.events.domain.model.TopRanksResult;
import com.jake.realtimeapi.events.domain.repository.RankingWarmthRepository;
import com.jake.realtimeapi.leaderboards.domain.model.Leaderboard;
import com.jake.realtimeapi.leaderboards.domain.repository.LeaderboardRepository;
import com.jake.realtimeapi.projects.domain.model.Project;
import com.jake.realtimeapi.projects.domain.repository.ProjectRepository;
import com.jake.realtimeapi.snapshots.application.command.CaptureSnapshotCommand;
import com.jake.realtimeapi.snapshots.application.usecase.CaptureSnapshotUseCase;
import com.jake.realtimeapi.events.persistence.redis.LeaderboardRedisKeyFactory;
import com.jake.realtimeapi.support.userid.UserIdCodec;
import com.jake.realtimeapi.users.domain.model.User;
import com.jake.realtimeapi.users.domain.repository.UserRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.GenericContainer;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 읽기 브레이커를 쓰기와 분리한 이유를 실제 컨테이너로 검증한다.
 * ① 쓰기 브레이커만 열려도 읽기는 Redis로 답한다. ② Redis가 죽으면 읽기는 500 대신 PostgreSQL 스냅샷으로 답한다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
// Redis 컨테이너를 죽인 채 끝나므로 컨텍스트를 다음 테스트 클래스에 물려주지 않는다
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RankingReadFallbackIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private LeaderboardRepository leaderboardRepository;

    @Autowired
    private CaptureSnapshotUseCase captureSnapshotUseCase;

    @Autowired
    private GetTopRanksUseCase getTopRanksUseCase;

    @Autowired
    private CircuitBreaker redisHotPathCircuitBreakerDelegate;

    @Autowired
    private RankingWarmthRepository rankingWarmthRepository;

    @Autowired
    private CircuitBreaker redisReadPathCircuitBreakerDelegate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private GenericContainer<?> redisContainer;

    @Test
    void readsSurviveWriteBreakerOpen_andFallBackToSnapshotWhenRedisDies() {
        redisReadPathCircuitBreakerDelegate.reset();
        String token = UUID.randomUUID().toString().substring(0, 8);
        List<User> users = IntStream.range(0, 3)
                .mapToObj(i -> userRepository.save(User.newUser("rf-" + token + "-" + i)))
                .toList();
        Project project = projectRepository.save(Project.newProject("rf-proj-" + UUID.randomUUID(), users.get(0).id()));
        UUID leaderboardId = leaderboardRepository.save(Leaderboard.newLeaderboard(project.id(), "rf-board")).id();
        rankingWarmthRepository.markWarm(leaderboardId);
        String rankingKey = LeaderboardRedisKeyFactory.rankingKey(leaderboardId);
        redisTemplate.opsForZSet().add(rankingKey, UserIdCodec.format(users.get(0).id()), 300);
        redisTemplate.opsForZSet().add(rankingKey, UserIdCodec.format(users.get(1).id()), 200);
        redisTemplate.opsForZSet().add(rankingKey, UserIdCodec.format(users.get(2).id()), 200);
        Instant snapshotAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        captureSnapshotUseCase.capture(new CaptureSnapshotCommand(leaderboardId, snapshotAt, 1000));
        GetTopRanksQuery query = new GetTopRanksQuery(leaderboardId, 0, 10);

        // ① 쓰기만 막힌 장애(예: noeviction OOM으로 Lua SET 거부)에서 읽기는 영향을 받지 않는다
        redisHotPathCircuitBreakerDelegate.transitionToForcedOpenState();
        try {
            assertEquals(RankingSource.REDIS, getTopRanksUseCase.getTopRanks(query).source());
        } finally {
            redisHotPathCircuitBreakerDelegate.reset();
        }

        // ② Redis 자체가 죽으면 스냅샷 시점의 같은 순위로 답한다
        redisContainer.stop();
        TopRanksResult fallback = getTopRanksUseCase.getTopRanks(query);

        assertEquals(RankingSource.SNAPSHOT, fallback.source());
        assertEquals(List.of(1, 2, 2), fallback.items().stream().map(TopRankItem::rank).toList());
        assertEquals(List.of(300L, 200L, 200L), fallback.items().stream().map(TopRankItem::score).toList());
        assertEquals(3L, fallback.total());
    }
}
