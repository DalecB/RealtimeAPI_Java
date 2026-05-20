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
        // lockKey를 leaderboard 단위로 고정해 동일 leaderboard의 중복 스냅샷을 차단한다.
        long lockKey = lockKey(leaderboardId);
        boolean locked = snapshotExecutionLockRepository.tryLock(lockKey);
        if (!locked) {
            return SnapshotCaptureExecutionResult.lockNotAcquired();
        }

        try {
            // 락을 획득한 실행만 실제 capture usecase를 호출한다.
            CaptureSnapshotResult result = captureSnapshotUseCase.capture(
                    new CaptureSnapshotCommand(leaderboardId, snapshotAt, topN)
            );
            return SnapshotCaptureExecutionResult.executed(result);
        } finally {
            // 예외 여부와 무관하게 락을 반환한다.
            snapshotExecutionLockRepository.unlock(lockKey);
        }
    }

    static long lockKey(UUID leaderboardId) {
        // PRD 기준으로 leaderboard hash를 advisory lock key로 사용한다.
        return leaderboardId.hashCode();
    }
}
