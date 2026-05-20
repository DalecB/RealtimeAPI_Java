package com.jake.realtimeapi.snapshots.worker;

import com.jake.realtimeapi.snapshots.application.SnapshotStatusTracker;
import com.jake.realtimeapi.snapshots.application.model.CaptureSnapshotResult;
import com.jake.realtimeapi.leaderboards.domain.repository.LeaderboardRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "snapshots.worker.enabled", havingValue = "true")
public class SnapshotCaptureWorker {

    private static final Logger log = LoggerFactory.getLogger(SnapshotCaptureWorker.class);

    private final LeaderboardRepository leaderboardRepository;
    private final SnapshotCaptureExecutionService snapshotCaptureExecutionService;
    private final SnapshotStatusTracker snapshotStatusTracker;
    private final Counter snapshotFailureCounter;
    private final Counter snapshotSkipCounter;
    private final Timer snapshotDurationTimer;
    private final int topN;
    private final int maxAttempts;
    private final long retryDelayMs;

    public SnapshotCaptureWorker(
            LeaderboardRepository leaderboardRepository,
            SnapshotCaptureExecutionService snapshotCaptureExecutionService,
            SnapshotStatusTracker snapshotStatusTracker,
            MeterRegistry meterRegistry,
            @Value("${snapshots.worker.top-n:1000}") int topN,
            @Value("${snapshots.worker.max-attempts:3}") int maxAttempts,
            @Value("${snapshots.worker.retry-delay-ms:5000}") long retryDelayMs
    ) {
        this.leaderboardRepository = leaderboardRepository;
        this.snapshotCaptureExecutionService = snapshotCaptureExecutionService;
        this.snapshotStatusTracker = snapshotStatusTracker;
        this.topN = topN;
        this.maxAttempts = maxAttempts;
        this.retryDelayMs = retryDelayMs;

        // PRD 관측 지표: 실패/스킵 카운터와 tracker 기반 lag gauge.
        this.snapshotFailureCounter = meterRegistry.counter("snapshot_failure_total");
        this.snapshotSkipCounter = meterRegistry.counter("snapshot_skip_total");
        this.snapshotDurationTimer = meterRegistry.timer("snapshot_duration_seconds");
        Gauge.builder("snapshot_lag_seconds", snapshotStatusTracker, SnapshotStatusTracker::snapshotLagSeconds)
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${snapshots.worker.delay-ms:30000}")
    public void captureAllLeaderboards() {
        // 주기마다 모든 leaderboard를 순회하며 snapshot을 시도한다.
        List<UUID> leaderboardIds = leaderboardRepository.findAllIds();
        if (leaderboardIds.isEmpty()) {
            return;
        }

        for (UUID leaderboardId : leaderboardIds) {
            snapshotDurationTimer.record(() -> captureSingleLeaderboard(leaderboardId));
        }
    }

    private void captureSingleLeaderboard(UUID leaderboardId) {
        // 동일 주기 내 재시도에서도 같은 snapshotAt을 사용해 동일 배치로 처리한다.
        Instant snapshotAt = Instant.now();

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                SnapshotCaptureExecutionResult execution = snapshotCaptureExecutionService.execute(leaderboardId, snapshotAt, topN);
                if (!execution.lockAcquired()) {
                    // 다른 인스턴스/스레드가 처리 중이면 현재 실행은 조용히 스킵.
                    log.debug("snapshots.capture skipped lock-not-acquired leaderboardId={}", leaderboardId);
                    return;
                }

                CaptureSnapshotResult result = execution.captureResult();
                if (result.skipped()) {
                    // Empty Guard 분기: 저장은 하지 않고 skip 메트릭만 증가.
                    snapshotSkipCounter.increment();
                    log.warn("snapshots.capture skipped empty leaderboardId={} snapshotAt={}", leaderboardId, snapshotAt);
                    return;
                }

                // 성공 시점은 tracker에 기록되고, 이후 internal API와 lag gauge가 이 값을 읽는다.
                snapshotStatusTracker.recordSuccess(snapshotAt);
                log.info(
                        "snapshots.capture success leaderboardId={} snapshotId={} rows={} snapshotAt={}",
                        leaderboardId,
                        result.snapshotId(),
                        result.rowCount(),
                        snapshotAt
                );
                return;
            } catch (RuntimeException ex) {
                if (attempt == maxAttempts) {
                    // 재시도 한도를 초과하면 실패 메트릭을 올리고 다음 대상 처리로 넘어간다.
                    snapshotFailureCounter.increment();
                    log.error("snapshots.capture failed leaderboardId={} attempts={}", leaderboardId, maxAttempts, ex);
                    return;
                }

                if (attempt >= 2) {
                    // PRD 정책: 첫 실패는 즉시 재시도, 이후 실패는 지연 후 재시도.
                    sleepRetryDelay();
                }
            }
        }
    }

    private void sleepRetryDelay() {
        if (retryDelayMs <= 0) {
            return;
        }

        try {
            Thread.sleep(retryDelayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("snapshots.capture retry interrupted", e);
        }
    }
}
