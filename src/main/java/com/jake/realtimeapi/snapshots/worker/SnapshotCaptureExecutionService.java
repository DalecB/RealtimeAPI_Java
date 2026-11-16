package com.jake.realtimeapi.snapshots.worker;

import com.jake.realtimeapi.snapshots.application.command.CaptureSnapshotCommand;
import com.jake.realtimeapi.snapshots.application.model.CaptureSnapshotResult;
import com.jake.realtimeapi.snapshots.application.usecase.CaptureSnapshotUseCase;
import com.jake.realtimeapi.snapshots.domain.repository.SnapshotExecutionLockRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class SnapshotCaptureExecutionService {

    private final CaptureSnapshotUseCase captureSnapshotUseCase;
    private final SnapshotExecutionLockRepository snapshotExecutionLockRepository;

    public SnapshotCaptureExecutionService(
            CaptureSnapshotUseCase captureSnapshotUseCase,
            SnapshotExecutionLockRepository snapshotExecutionLockRepository
    ) {
        this.captureSnapshotUseCase = captureSnapshotUseCase;
        this.snapshotExecutionLockRepository = snapshotExecutionLockRepository;
    }

    @Transactional
    public SnapshotCaptureExecutionResult execute(UUID leaderboardId, Instant snapshotAt, int topN) {
        boolean locked = snapshotExecutionLockRepository.tryLockForCurrentTransaction(lockKey(leaderboardId));
        if (!locked) {
            return SnapshotCaptureExecutionResult.lockNotAcquired();
        }

        CaptureSnapshotResult result = captureSnapshotUseCase.capture(
                new CaptureSnapshotCommand(leaderboardId, snapshotAt, topN)
        );
        return SnapshotCaptureExecutionResult.executed(result);
    }

    static long lockKey(UUID leaderboardId) {
        return leaderboardId.getMostSignificantBits() ^ leaderboardId.getLeastSignificantBits();
    }
}
