package com.jake.realtimeapi.snapshots.worker;

import com.jake.realtimeapi.snapshots.application.command.CaptureSnapshotCommand;
import com.jake.realtimeapi.snapshots.application.model.CaptureSnapshotResult;
import com.jake.realtimeapi.snapshots.application.usecase.CaptureSnapshotUseCase;
import com.jake.realtimeapi.snapshots.domain.repository.SnapshotExecutionLockRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SnapshotCaptureExecutionServiceTest {

    @Mock
    private CaptureSnapshotUseCase captureSnapshotUseCase;

    @Mock
    private SnapshotExecutionLockRepository snapshotExecutionLockRepository;

    @Test
    void execute_returnsLockNotAcquiredWhenTryLockFails() {
        SnapshotCaptureExecutionService service =
                new SnapshotCaptureExecutionService(captureSnapshotUseCase, snapshotExecutionLockRepository);
        UUID leaderboardId = UUID.randomUUID();
        Instant snapshotAt = Instant.parse("2026-03-18T00:00:00Z");

        when(snapshotExecutionLockRepository.tryLock(anyLong())).thenReturn(false);

        SnapshotCaptureExecutionResult result = service.execute(leaderboardId, snapshotAt, 1000);

        assertFalse(result.lockAcquired());
        verify(captureSnapshotUseCase, never()).capture(any());
        verify(snapshotExecutionLockRepository, never()).unlock(anyLong());
    }

    @Test
    void execute_capturesAndUnlocksWhenLockAcquired() {
        SnapshotCaptureExecutionService service =
                new SnapshotCaptureExecutionService(captureSnapshotUseCase, snapshotExecutionLockRepository);
        UUID leaderboardId = UUID.randomUUID();
        Instant snapshotAt = Instant.parse("2026-03-18T00:00:00Z");
        long lockKey = SnapshotCaptureExecutionService.lockKey(leaderboardId);
        CaptureSnapshotResult captured = CaptureSnapshotResult.captured(leaderboardId, snapshotAt, 1000, 10L, 1000);

        when(snapshotExecutionLockRepository.tryLock(lockKey)).thenReturn(true);
        when(captureSnapshotUseCase.capture(new CaptureSnapshotCommand(leaderboardId, snapshotAt, 1000)))
                .thenReturn(captured);

        SnapshotCaptureExecutionResult result = service.execute(leaderboardId, snapshotAt, 1000);

        assertTrue(result.lockAcquired());
        assertEquals(captured, result.captureResult());
        verify(snapshotExecutionLockRepository).unlock(lockKey);
    }

    @Test
    void execute_unlocksWhenCaptureThrows() {
        SnapshotCaptureExecutionService service =
                new SnapshotCaptureExecutionService(captureSnapshotUseCase, snapshotExecutionLockRepository);
        UUID leaderboardId = UUID.randomUUID();
        Instant snapshotAt = Instant.parse("2026-03-18T00:00:00Z");
        long lockKey = SnapshotCaptureExecutionService.lockKey(leaderboardId);

        when(snapshotExecutionLockRepository.tryLock(lockKey)).thenReturn(true);
        when(captureSnapshotUseCase.capture(new CaptureSnapshotCommand(leaderboardId, snapshotAt, 1000)))
                .thenThrow(new IllegalStateException("boom"));

        assertThrows(IllegalStateException.class, () -> service.execute(leaderboardId, snapshotAt, 1000));
        verify(snapshotExecutionLockRepository).unlock(lockKey);
    }
}
