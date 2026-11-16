package com.jake.realtimeapi.snapshots.worker;

import com.jake.realtimeapi.leaderboards.domain.repository.LeaderboardRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 앱 시작 시 cold(warm 키 없음)인 리더보드를 스냅샷으로 되살린다. 스냅샷 워커도 매 주기 같은 일을 하지만,
 * 시작 직후 첫 주기를 기다리지 않게 한다. 복구 중 쓰기는 Lua가 거부하고 읽기는 스냅샷으로 가므로 브레이커를 따로 열지 않는다.
 */
@Component
@ConditionalOnProperty(name = "snapshots.recovery.enabled", havingValue = "true", matchIfMissing = true)
public class ColdStartRecoveryRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ColdStartRecoveryRunner.class);

    private final LeaderboardRepository leaderboardRepository;
    private final SnapshotCaptureExecutionService snapshotCaptureExecutionService;
    private final Counter coldStartRecoveryCounter;
    private final int topN;

    public ColdStartRecoveryRunner(
            LeaderboardRepository leaderboardRepository,
            SnapshotCaptureExecutionService snapshotCaptureExecutionService,
            MeterRegistry meterRegistry,
            @Value("${snapshots.recovery.top-n:1000}") int topN
    ) {
        this.leaderboardRepository = leaderboardRepository;
        this.snapshotCaptureExecutionService = snapshotCaptureExecutionService;
        this.coldStartRecoveryCounter = meterRegistry.counter("cold_start_recovery_total");
        this.topN = topN;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (var leaderboardId : leaderboardRepository.findAllIds()) {
            snapshotCaptureExecutionService.recover(leaderboardId, topN).ifPresentOrElse(result -> {
                if (result.recovered()) {
                    coldStartRecoveryCounter.increment();
                    log.info("cold-start.recovery restored leaderboardId={} rows={} snapshotAt={}",
                            leaderboardId, result.restoredRowCount(), result.snapshotAt());
                } else {
                    log.info("cold-start.recovery skipped leaderboardId={} reason={}", leaderboardId, result.reason());
                }
            }, () -> log.info("cold-start.recovery skipped leaderboardId={} reason=LOCK_HELD_BY_OTHER_INSTANCE", leaderboardId));
        }
    }
}
