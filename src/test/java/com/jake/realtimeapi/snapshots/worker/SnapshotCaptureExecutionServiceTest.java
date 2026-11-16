package com.jake.realtimeapi.snapshots.worker;

import com.jake.realtimeapi.snapshots.application.command.CaptureSnapshotCommand;
import com.jake.realtimeapi.snapshots.application.command.RecoverLeaderboardSnapshotCommand;
import com.jake.realtimeapi.snapshots.application.model.CaptureSnapshotResult;
import com.jake.realtimeapi.snapshots.application.model.RecoverLeaderboardSnapshotResult;
import com.jake.realtimeapi.snapshots.application.usecase.CaptureSnapshotUseCase;
import com.jake.realtimeapi.snapshots.application.usecase.RecoverLeaderboardSnapshotUseCase;
import com.jake.realtimeapi.snapshots.domain.repository.SnapshotExecutionLockRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SnapshotCaptureExecutionServiceTest {

    @Mock
    private CaptureSnapshotUseCase captureSnapshotUseCase;

    @Mock
    private RecoverLeaderboardSnapshotUseCase recoverLeaderboardSnapshotUseCase;

    @Mock
    private SnapshotExecutionLockRepository snapshotExecutionLockRepository;

    private SnapshotCaptureExecutionService service() {
        return new SnapshotCaptureExecutionService(captureSnapshotUseCase, recoverLeaderboardSnapshotUseCase, snapshotExecutionLockRepository);
    }

    @Test
    void lockKey_usesBothUuidHalves() {
        UUID leaderboardId = UUID.fromString("00000000-0000-4000-8000-000000000000");

        assertEquals(Long.MIN_VALUE + 16384L, SnapshotCaptureExecutionService.lockKey(leaderboardId));
    }

    @Test
    void execute_returnsLockNotAcquiredWhenTryLockFails() {
        UUID leaderboardId = UUID.randomUUID();
        Instant snapshotAt = Instant.parse("2026-03-18T00:00:00Z");

        when(snapshotExecutionLockRepository.tryLockForCurrentTransaction(anyLong())).thenReturn(false);

        SnapshotCaptureExecutionResult result = service().execute(leaderboardId, snapshotAt, 1000);

        assertFalse(result.lockAcquired());
        verify(captureSnapshotUseCase, never()).capture(any());
        verify(recoverLeaderboardSnapshotUseCase, never()).recover(any());
    }

    @Test
    void execute_recoversColdRankingBeforeCapturing() {
        UUID leaderboardId = UUID.randomUUID();
        Instant snapshotAt = Instant.parse("2026-03-18T00:00:00Z");
        long lockKey = SnapshotCaptureExecutionService.lockKey(leaderboardId);
        CaptureSnapshotResult captured = CaptureSnapshotResult.captured(leaderboardId, snapshotAt, 1000, 10L, 1000);
        RecoverLeaderboardSnapshotResult recovered = RecoverLeaderboardSnapshotResult.recovered(leaderboardId, 1000, snapshotAt);

        when(snapshotExecutionLockRepository.tryLockForCurrentTransaction(lockKey)).thenReturn(true);
        when(recoverLeaderboardSnapshotUseCase.recover(new RecoverLeaderboardSnapshotCommand(leaderboardId, 1000)))
                .thenReturn(recovered);
        when(captureSnapshotUseCase.capture(new CaptureSnapshotCommand(leaderboardId, snapshotAt, 1000)))
                .thenReturn(captured);

        SnapshotCaptureExecutionResult result = service().execute(leaderboardId, snapshotAt, 1000);

        assertTrue(result.lockAcquired());
        assertEquals(recovered, result.recoveryResult());
        assertEquals(captured, result.captureResult());
        // 캡처가 일부만 남은 랭킹을 읽지 않도록 복구가 먼저다
        InOrder inOrder = inOrder(recoverLeaderboardSnapshotUseCase, captureSnapshotUseCase);
        inOrder.verify(recoverLeaderboardSnapshotUseCase).recover(any());
        inOrder.verify(captureSnapshotUseCase).capture(any());
    }

    @Test
    void recover_skipsWhenAnotherInstanceHoldsTheLock() {
        when(snapshotExecutionLockRepository.tryLockForCurrentTransaction(anyLong())).thenReturn(false);

        assertTrue(service().recover(UUID.randomUUID(), 1000).isEmpty());
        verify(recoverLeaderboardSnapshotUseCase, never()).recover(any());
    }
}
