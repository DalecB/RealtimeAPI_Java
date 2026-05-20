package com.jake.realtimeapi.snapshots.worker;

import com.jake.realtimeapi.leaderboards.domain.repository.LeaderboardRepository;
import com.jake.realtimeapi.snapshots.application.SnapshotStatusTracker;
import com.jake.realtimeapi.snapshots.application.model.CaptureSnapshotResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SnapshotCaptureWorkerTest {

    @Mock
    private LeaderboardRepository leaderboardRepository;

    @Mock
    private SnapshotCaptureExecutionService snapshotCaptureExecutionService;

    @Mock
    private SnapshotStatusTracker snapshotStatusTracker;

    @Test
    void captureAllLeaderboards_doesNothingWhenNoLeaderboardExists() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        SnapshotCaptureWorker worker = new SnapshotCaptureWorker(
                leaderboardRepository,
                snapshotCaptureExecutionService,
                snapshotStatusTracker,
                meterRegistry,
                1000,
                3,
                0
        );

        when(leaderboardRepository.findAllIds()).thenReturn(List.of());

        worker.captureAllLeaderboards();

        verify(snapshotCaptureExecutionService, never()).execute(any(), any(), anyInt());
    }

    @Test
    void captureAllLeaderboards_incrementsSkipMetricWhenCaptureResultIsSkipped() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        SnapshotCaptureWorker worker = new SnapshotCaptureWorker(
                leaderboardRepository,
                snapshotCaptureExecutionService,
                snapshotStatusTracker,
                meterRegistry,
                1000,
                3,
                0
        );
        UUID leaderboardId = UUID.randomUUID();
        Instant snapshotAt = Instant.parse("2026-03-18T00:00:00Z");

        when(leaderboardRepository.findAllIds()).thenReturn(List.of(leaderboardId));
        when(snapshotCaptureExecutionService.execute(any(), any(), anyInt()))
                .thenReturn(SnapshotCaptureExecutionResult.executed(
                        CaptureSnapshotResult.skipped(leaderboardId, snapshotAt, 1000)
                ));

        worker.captureAllLeaderboards();

        assertEquals(1.0, meterRegistry.get("snapshot_skip_total").counter().count());
    }

    @Test
    void captureAllLeaderboards_retriesAndIncrementsFailureMetricWhenAllAttemptsFail() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        SnapshotCaptureWorker worker = new SnapshotCaptureWorker(
                leaderboardRepository,
                snapshotCaptureExecutionService,
                snapshotStatusTracker,
                meterRegistry,
                1000,
                3,
                0
        );
        UUID leaderboardId = UUID.randomUUID();

        when(leaderboardRepository.findAllIds()).thenReturn(List.of(leaderboardId));
        when(snapshotCaptureExecutionService.execute(any(), any(), anyInt()))
                .thenThrow(new IllegalStateException("boom"));

        worker.captureAllLeaderboards();

        verify(snapshotCaptureExecutionService, times(3)).execute(any(), any(), anyInt());
        assertEquals(1.0, meterRegistry.get("snapshot_failure_total").counter().count());
    }
}
