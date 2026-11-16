package com.jake.realtimeapi.snapshots.worker;

import com.jake.realtimeapi.infra.circuitbreaker.RedisHotPathCircuitBreaker;
import com.jake.realtimeapi.infra.circuitbreaker.RedisReadPathCircuitBreaker;
import com.jake.realtimeapi.leaderboards.domain.repository.LeaderboardRepository;
import com.jake.realtimeapi.snapshots.application.model.RecoverLeaderboardSnapshotResult;
import com.jake.realtimeapi.snapshots.application.usecase.RecoverLeaderboardSnapshotUseCase;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ColdStartRecoveryRunnerTest {

    @Mock
    private LeaderboardRepository leaderboardRepository;

    @Mock
    private RecoverLeaderboardSnapshotUseCase recoverLeaderboardSnapshotUseCase;

    @Mock
    private RedisHotPathCircuitBreaker redisHotPathCircuitBreaker;

    @Mock
    private RedisReadPathCircuitBreaker redisReadPathCircuitBreaker;

    @Test
    void run_opensBreakerRestoresAllLeaderboardsAndClearsManualOverride() throws Exception {
        UUID leaderboardA = UUID.randomUUID();
        UUID leaderboardB = UUID.randomUUID();
        when(leaderboardRepository.findAllIds()).thenReturn(List.of(leaderboardA, leaderboardB));
        when(recoverLeaderboardSnapshotUseCase.recover(any()))
                .thenReturn(RecoverLeaderboardSnapshotResult.skipped(leaderboardA, "REDIS_ALREADY_WARM"))
                .thenReturn(RecoverLeaderboardSnapshotResult.recovered(leaderboardB, 10, Instant.parse("2026-03-20T00:00:00Z")));

        ColdStartRecoveryRunner runner = new ColdStartRecoveryRunner(
                leaderboardRepository,
                recoverLeaderboardSnapshotUseCase,
                redisHotPathCircuitBreaker,
                redisReadPathCircuitBreaker,
                new SimpleMeterRegistry(),
                1000
        );

        runner.run(new DefaultApplicationArguments(new String[0]));

        // 쓰기는 오염 방지로 막고, 읽기는 반쯤 찬 ZSET 대신 스냅샷으로 돌린다. 복구가 끝나면 둘 다 푼다.
        InOrder inOrder = inOrder(redisHotPathCircuitBreaker, redisReadPathCircuitBreaker, recoverLeaderboardSnapshotUseCase);
        inOrder.verify(redisHotPathCircuitBreaker).openManually("cold-start-recovery");
        inOrder.verify(redisReadPathCircuitBreaker).openManually("cold-start-recovery");
        inOrder.verify(recoverLeaderboardSnapshotUseCase, times(2)).recover(any());
        inOrder.verify(redisHotPathCircuitBreaker).clearManualOverride();
        inOrder.verify(redisReadPathCircuitBreaker).clearManualOverride();
        verify(leaderboardRepository).findAllIds();
    }
}
