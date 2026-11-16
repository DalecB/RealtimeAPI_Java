package com.jake.realtimeapi.snapshots.worker;

import com.jake.realtimeapi.leaderboards.domain.repository.LeaderboardRepository;
import com.jake.realtimeapi.snapshots.application.model.RecoverLeaderboardSnapshotResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ColdStartRecoveryRunnerTest {

    @Mock
    private LeaderboardRepository leaderboardRepository;

    @Mock
    private SnapshotCaptureExecutionService snapshotCaptureExecutionService;

    @Test
    void run_recoversEveryLeaderboardThroughTheSnapshotLockAndCountsRestores() throws Exception {
        UUID warm = UUID.randomUUID();
        UUID cold = UUID.randomUUID();
        UUID lockedByOther = UUID.randomUUID();
        when(leaderboardRepository.findAllIds()).thenReturn(List.of(warm, cold, lockedByOther));
        when(snapshotCaptureExecutionService.recover(warm, 1000))
                .thenReturn(Optional.of(RecoverLeaderboardSnapshotResult.skipped(warm, "WARM")));
        when(snapshotCaptureExecutionService.recover(cold, 1000))
                .thenReturn(Optional.of(RecoverLeaderboardSnapshotResult.recovered(cold, 10, Instant.parse("2026-03-20T00:00:00Z"))));
        when(snapshotCaptureExecutionService.recover(lockedByOther, 1000)).thenReturn(Optional.empty());
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

        new ColdStartRecoveryRunner(leaderboardRepository, snapshotCaptureExecutionService, meterRegistry, 1000)
                .run(new DefaultApplicationArguments(new String[0]));

        // 복구는 스냅샷 워커와 같은 advisory lock 아래에서만 한다. 다른 인스턴스가 잡고 있으면 건너뛴다.
        verify(snapshotCaptureExecutionService).recover(lockedByOther, 1000);
        assertEquals(1.0, meterRegistry.get("cold_start_recovery_total").counter().count());
    }
}
