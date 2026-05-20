package com.jake.realtimeapi.snapshots.worker;

import com.jake.realtimeapi.infra.circuitbreaker.RedisHotPathCircuitBreaker;
import com.jake.realtimeapi.leaderboards.domain.repository.LeaderboardRepository;
import com.jake.realtimeapi.snapshots.application.command.RecoverLeaderboardSnapshotCommand;
import com.jake.realtimeapi.snapshots.application.model.RecoverLeaderboardSnapshotResult;
import com.jake.realtimeapi.snapshots.application.usecase.RecoverLeaderboardSnapshotUseCase;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "snapshots.recovery.enabled", havingValue = "true", matchIfMissing = true)
public class ColdStartRecoveryRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ColdStartRecoveryRunner.class);

    private final LeaderboardRepository leaderboardRepository;
    private final RecoverLeaderboardSnapshotUseCase recoverLeaderboardSnapshotUseCase;
    private final RedisHotPathCircuitBreaker redisHotPathCircuitBreaker;
    private final Counter coldStartRecoveryCounter;
    private final int topN;

    public ColdStartRecoveryRunner(
            LeaderboardRepository leaderboardRepository,
            RecoverLeaderboardSnapshotUseCase recoverLeaderboardSnapshotUseCase,
            RedisHotPathCircuitBreaker redisHotPathCircuitBreaker,
            MeterRegistry meterRegistry,
            @Value("${snapshots.recovery.top-n:1000}") int topN
    ) {
        this.leaderboardRepository = leaderboardRepository;
        this.recoverLeaderboardSnapshotUseCase = recoverLeaderboardSnapshotUseCase;
        this.redisHotPathCircuitBreaker = redisHotPathCircuitBreaker;
        this.coldStartRecoveryCounter = meterRegistry.counter("cold_start_recovery_total");
        this.topN = topN;
    }

    @Override
    public void run(ApplicationArguments args) {
        // PRD 요구: recovery 중에는 write 오염을 막기 위해 breaker를 수동 OPEN 한다.
        redisHotPathCircuitBreaker.openManually("cold-start-recovery");
        try {
            for (var leaderboardId : leaderboardRepository.findAllIds()) {
                RecoverLeaderboardSnapshotResult result = recoverLeaderboardSnapshotUseCase.recover(
                        new RecoverLeaderboardSnapshotCommand(leaderboardId, topN)
                );

                if (result.recovered()) {
                    coldStartRecoveryCounter.increment();
                    log.info("cold-start.recovery restored leaderboardId={} rows={} snapshotAt={}",
                            leaderboardId, result.restoredRowCount(), result.snapshotAt());
                } else {
                    log.info("cold-start.recovery skipped leaderboardId={} reason={}", leaderboardId, result.reason());
                }
            }
        } finally {
            redisHotPathCircuitBreaker.clearManualOverride();
        }
    }
}
