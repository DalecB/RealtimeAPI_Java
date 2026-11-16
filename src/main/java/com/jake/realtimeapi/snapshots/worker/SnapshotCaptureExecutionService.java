package com.jake.realtimeapi.snapshots.worker;

import com.jake.realtimeapi.snapshots.application.command.CaptureSnapshotCommand;
import com.jake.realtimeapi.snapshots.application.command.RecoverLeaderboardSnapshotCommand;
import com.jake.realtimeapi.snapshots.application.model.CaptureSnapshotResult;
import com.jake.realtimeapi.snapshots.application.model.RecoverLeaderboardSnapshotResult;
import com.jake.realtimeapi.snapshots.application.usecase.CaptureSnapshotUseCase;
import com.jake.realtimeapi.snapshots.application.usecase.RecoverLeaderboardSnapshotUseCase;
import com.jake.realtimeapi.snapshots.domain.repository.SnapshotExecutionLockRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class SnapshotCaptureExecutionService {

    private final CaptureSnapshotUseCase captureSnapshotUseCase;
    private final RecoverLeaderboardSnapshotUseCase recoverLeaderboardSnapshotUseCase;
    private final SnapshotExecutionLockRepository snapshotExecutionLockRepository;

    public SnapshotCaptureExecutionService(
            CaptureSnapshotUseCase captureSnapshotUseCase,
            RecoverLeaderboardSnapshotUseCase recoverLeaderboardSnapshotUseCase,
            SnapshotExecutionLockRepository snapshotExecutionLockRepository
    ) {
        this.captureSnapshotUseCase = captureSnapshotUseCase;
        this.recoverLeaderboardSnapshotUseCase = recoverLeaderboardSnapshotUseCase;
        this.snapshotExecutionLockRepository = snapshotExecutionLockRepository;
    }

    @Transactional
    public SnapshotCaptureExecutionResult execute(UUID leaderboardId, Instant snapshotAt, int topN) {
        boolean locked = snapshotExecutionLockRepository.tryLockForCurrentTransaction(lockKey(leaderboardId));
        if (!locked) {
            return SnapshotCaptureExecutionResult.lockNotAcquired();
        }

        // Redis가 랭킹을 잃었으면(warm 키 없음) 캡처 전에 먼저 되살린다. 앱이 떠 있는 동안 유실돼도 한 주기 안에 복구된다.
        RecoverLeaderboardSnapshotResult recovery = recoverLeaderboardSnapshotUseCase.recover(
                new RecoverLeaderboardSnapshotCommand(leaderboardId, topN)
        );
        CaptureSnapshotResult result = captureSnapshotUseCase.capture(
                new CaptureSnapshotCommand(leaderboardId, snapshotAt, topN)
        );
        return SnapshotCaptureExecutionResult.executed(recovery, result);
    }

    /**
     * 캡처와 같은 락 아래에서 복구만 한다(앱 시작 시). 두 인스턴스가 동시에 ZSET을 갈아끼우면
     * 먼저 끝난 쪽이 warm으로 바꾼 뒤 들어온 쓰기를 다른 쪽이 덮어쓸 수 있어서다. 락을 못 잡으면 다른 인스턴스가 하고 있다.
     */
    @Transactional
    public Optional<RecoverLeaderboardSnapshotResult> recover(UUID leaderboardId, int topN) {
        if (!snapshotExecutionLockRepository.tryLockForCurrentTransaction(lockKey(leaderboardId))) {
            return Optional.empty();
        }
        return Optional.of(recoverLeaderboardSnapshotUseCase.recover(new RecoverLeaderboardSnapshotCommand(leaderboardId, topN)));
    }

    static long lockKey(UUID leaderboardId) {
        return leaderboardId.getMostSignificantBits() ^ leaderboardId.getLeastSignificantBits();
    }
}
