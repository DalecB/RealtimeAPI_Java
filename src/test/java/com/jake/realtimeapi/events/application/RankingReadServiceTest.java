package com.jake.realtimeapi.events.application;

import com.jake.realtimeapi.events.application.query.GetTopRanksQuery;
import com.jake.realtimeapi.events.application.query.GetUserRankQuery;
import com.jake.realtimeapi.events.domain.model.RankingSnapshot;
import com.jake.realtimeapi.events.domain.model.RankingSource;
import com.jake.realtimeapi.events.domain.model.TopRankItem;
import com.jake.realtimeapi.events.domain.model.TopRanksResult;
import com.jake.realtimeapi.events.domain.model.UserRankResult;
import com.jake.realtimeapi.events.domain.repository.RankingQueryRepository;
import com.jake.realtimeapi.events.domain.repository.RankingSnapshotRepository;
import com.jake.realtimeapi.infra.circuitbreaker.RankingReadUnavailableException;
import com.jake.realtimeapi.infra.circuitbreaker.RedisReadPathCircuitBreaker;
import com.jake.realtimeapi.infra.circuitbreaker.RedisReadPathCircuitBreakerConfig;
import com.jake.realtimeapi.infra.circuitbreaker.RedisReadPathCircuitBreakerProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 읽기 브레이커 + 스냅샷 fallback 판정 단위 테스트. 브레이커는 운영 설정값(10건·50%·100ms)을 그대로 쓰는 진짜 resilience4j 인스턴스다.
 */
class RankingReadServiceTest {

    private static final UUID LEADERBOARD = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-16T12:00:00Z");
    private static final Instant SNAPSHOT_AT = NOW.minusSeconds(20);
    private static final RedisReadPathCircuitBreakerProperties PROPERTIES = new RedisReadPathCircuitBreakerProperties(
            10, 50, Duration.ofMillis(100), 50, Duration.ofSeconds(10), 3);

    private final RankingQueryRepository redis = mock(RankingQueryRepository.class);
    private final RankingSnapshotRepository snapshots = mock(RankingSnapshotRepository.class);
    private CircuitBreaker delegate;
    private RedisReadPathCircuitBreaker breaker;

    @BeforeEach
    void setUp() {
        delegate = new RedisReadPathCircuitBreakerConfig().redisReadPathCircuitBreakerDelegate(PROPERTIES);
        breaker = new RedisReadPathCircuitBreaker(delegate, new SimpleMeterRegistry());
    }

    private RankingReadService service(Instant now) {
        return new RankingReadService(redis, snapshots, breaker, Duration.ofMinutes(10), Clock.fixed(now, ZoneOffset.UTC));
    }

    // 1위 300, 2위 동점 200·200, 4위 100
    private static RankingSnapshot snapshot(Instant at, int topN) {
        return new RankingSnapshot(at, topN, List.of(
                new TopRankItem(1, 11L, 300L),
                new TopRankItem(2, 12L, 200L),
                new TopRankItem(2, 13L, 200L),
                new TopRankItem(4, 14L, 100L)));
    }

    private void redisDown() {
        when(redis.findTopByLeaderboardId(any(), anyInt(), anyInt())).thenThrow(new RedisConnectionFailureException("down"));
        when(redis.findUserRank(any(), anyLong())).thenThrow(new RedisConnectionFailureException("down"));
    }

    @Test
    void redisHealthy_answersFromRedis() {
        when(redis.findTopByLeaderboardId(LEADERBOARD, 0, 10)).thenReturn(List.of(new TopRankItem(1, 11L, 300L)));
        when(redis.countParticipants(LEADERBOARD)).thenReturn(1L);

        TopRanksResult result = service(NOW).getTopRanks(new GetTopRanksQuery(LEADERBOARD, 0, 10));

        assertEquals(RankingSource.REDIS, result.source());
        assertNull(result.asOf());
        assertEquals(1L, result.total());
    }

    @Test
    void redisFails_answersFromSnapshotWithItsTimestamp() {
        redisDown();
        when(snapshots.findLatest(LEADERBOARD)).thenReturn(Optional.of(snapshot(SNAPSHOT_AT, 1000)));

        TopRanksResult result = service(NOW).getTopRanks(new GetTopRanksQuery(LEADERBOARD, 0, 10));

        assertEquals(RankingSource.SNAPSHOT, result.source());
        assertEquals(SNAPSHOT_AT, result.asOf());
        assertEquals(List.of(1, 2, 2, 4), result.items().stream().map(TopRankItem::rank).toList());
        // 4명 < topN 1,000 이면 캡처 당시 전원이 들어 있으므로 total을 안다
        assertEquals(4L, result.total());
    }

    @Test
    void truncatedSnapshot_hasUnknownTotalAndRefusesPagesPastTopN() {
        redisDown();
        when(snapshots.findLatest(LEADERBOARD)).thenReturn(Optional.of(snapshot(SNAPSHOT_AT, 4)));
        RankingReadService service = service(NOW);

        TopRanksResult firstPage = service.getTopRanks(new GetTopRanksQuery(LEADERBOARD, 0, 2));
        assertNull(firstPage.total());

        RankingReadUnavailableException pastTopN = assertThrows(RankingReadUnavailableException.class,
                () -> service.getTopRanks(new GetTopRanksQuery(LEADERBOARD, 2, 10)));
        assertEquals(RankingReadUnavailableException.Reason.NOT_IN_SNAPSHOT, pastTopN.getReason());
    }

    @Test
    void userOutsideTruncatedSnapshot_isUnavailableNotUnranked() {
        redisDown();
        when(snapshots.findLatest(LEADERBOARD)).thenReturn(Optional.of(snapshot(SNAPSHOT_AT, 4)));

        RankingReadUnavailableException exception = assertThrows(RankingReadUnavailableException.class,
                () -> service(NOW).getUserRank(new GetUserRankQuery(LEADERBOARD, 99L)));

        assertEquals(RankingReadUnavailableException.Reason.NOT_IN_SNAPSHOT, exception.getReason());
        assertEquals(10L, exception.getRetryAfterSeconds());
    }

    @Test
    void userAbsentFromCompleteSnapshot_isNotParticipating() {
        redisDown();
        when(snapshots.findLatest(LEADERBOARD)).thenReturn(Optional.of(snapshot(SNAPSHOT_AT, 1000)));
        RankingReadService service = service(NOW);

        UserRankResult tied = service.getUserRank(new GetUserRankQuery(LEADERBOARD, 13L));
        UserRankResult absent = service.getUserRank(new GetUserRankQuery(LEADERBOARD, 99L));

        assertEquals(2, tied.rank());
        assertEquals(RankingSource.SNAPSHOT, tied.source());
        assertEquals(0L, absent.score());
        assertNull(absent.rank());
    }

    @Test
    void snapshotOlderThanTenMinutes_isRefused() {
        redisDown();
        when(snapshots.findLatest(LEADERBOARD)).thenReturn(Optional.of(snapshot(NOW.minus(Duration.ofMinutes(10)).minusSeconds(1), 1000)));

        RankingReadUnavailableException exception = assertThrows(RankingReadUnavailableException.class,
                () -> service(NOW).getTopRanks(new GetTopRanksQuery(LEADERBOARD, 0, 10)));

        assertEquals(RankingReadUnavailableException.Reason.SNAPSHOT_TOO_OLD, exception.getReason());
    }

    @Test
    void noSnapshot_isRefused() {
        redisDown();
        when(snapshots.findLatest(LEADERBOARD)).thenReturn(Optional.empty());

        RankingReadUnavailableException exception = assertThrows(RankingReadUnavailableException.class,
                () -> service(NOW).getTopRanks(new GetTopRanksQuery(LEADERBOARD, 0, 10)));

        assertEquals(RankingReadUnavailableException.Reason.NO_SNAPSHOT, exception.getReason());
    }

    @Test
    void slowRedis_opensBreakerAndStopsCallingRedis() {
        // Redis가 죽지 않고 느리기만 한 경우: 실패는 0이어도 100ms 초과가 창(10건)의 50%를 넘으면 열려야 한다
        when(redis.findUserRank(any(), anyLong())).thenAnswer(invocation -> {
            Thread.sleep(120);
            return new UserRankResult(11L, 300L, 1);
        });
        when(snapshots.findLatest(LEADERBOARD)).thenReturn(Optional.of(snapshot(SNAPSHOT_AT, 1000)));
        RankingReadService service = service(NOW);

        for (int i = 0; i < 10; i++) {
            assertEquals(RankingSource.REDIS, service.getUserRank(new GetUserRankQuery(LEADERBOARD, 11L)).source());
        }

        assertEquals(CircuitBreaker.State.OPEN, delegate.getState());
        assertEquals(RankingSource.SNAPSHOT, service.getUserRank(new GetUserRankQuery(LEADERBOARD, 11L)).source());
        verify(redis, times(10)).findUserRank(any(), anyLong());
    }

    @Test
    void manualOpenDuringRecovery_readsSnapshotWithoutTouchingRedis() {
        when(snapshots.findLatest(LEADERBOARD)).thenReturn(Optional.of(snapshot(SNAPSHOT_AT, 1000)));
        breaker.openManually("cold-start-recovery");

        TopRanksResult result = service(NOW).getTopRanks(new GetTopRanksQuery(LEADERBOARD, 0, 10));

        assertEquals(RankingSource.SNAPSHOT, result.source());
        verify(redis, times(0)).findTopByLeaderboardId(any(), anyInt(), anyInt());
    }

    @Test
    void snapshotIsCachedAcrossRequests() {
        redisDown();
        when(snapshots.findLatest(LEADERBOARD)).thenReturn(Optional.of(snapshot(SNAPSHOT_AT, 1000)));
        RankingReadService service = service(NOW);

        for (int i = 0; i < 5; i++) {
            service.getTopRanks(new GetTopRanksQuery(LEADERBOARD, 0, 10));
        }

        // Redis 장애 중 읽기 트래픽이 PostgreSQL로 그대로 넘어가지 않는다
        verify(snapshots, times(1)).findLatest(LEADERBOARD);
    }
}
