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
        // lockKey를 leaderboard 단위로 고정해 동일 leaderboard의 중복 스냅샷을 차단한다.
        long lockKey = lockKey(leaderboardId);
        boolean locked = snapshotExecutionLockRepository.tryLock(lockKey);
        if (!locked) {
            return SnapshotCaptureExecutionResult.lockNotAcquired();
        }

        try {
            // Redis가 랭킹을 잃었으면(warm 키 없음) 캡처 전에 먼저 되살린다. 앱이 떠 있는 동안 유실돼도 한 주기 안에 복구된다.
            RecoverLeaderboardSnapshotResult recovery = recoverLeaderboardSnapshotUseCase.recover(
                    new RecoverLeaderboardSnapshotCommand(leaderboardId, topN)
            );
            // 락을 획득한 실행만 실제 capture usecase를 호출한다.
            CaptureSnapshotResult result = captureSnapshotUseCase.capture(
                    new CaptureSnapshotCommand(leaderboardId, snapshotAt, topN)
            );
            return SnapshotCaptureExecutionResult.executed(recovery, result);
        } finally {
            // 예외 여부와 무관하게 락을 반환한다.
            snapshotExecutionLockRepository.unlock(lockKey);
        }
    }

    /**
     * 캡처와 같은 락 아래에서 복구만 한다(앱 시작 시). 두 인스턴스가 동시에 ZSET을 갈아끼우면
     * 먼저 끝난 쪽이 warm으로 바꾼 뒤 들어온 쓰기를 다른 쪽이 덮어쓸 수 있어서다. 락을 못 잡으면 다른 인스턴스가 하고 있다.
     */
    @Transactional
    public Optional<RecoverLeaderboardSnapshotResult> recover(UUID leaderboardId, int topN) {
        long lockKey = lockKey(leaderboardId);
        if (!snapshotExecutionLockRepository.tryLock(lockKey)) {
            return Optional.empty();
        }
        try {
            return Optional.of(recoverLeaderboardSnapshotUseCase.recover(new RecoverLeaderboardSnapshotCommand(leaderboardId, topN)));
        } finally {
            snapshotExecutionLockRepository.unlock(lockKey);
        }
    }

    static long lockKey(UUID leaderboardId) {
        return leaderboardId.getMostSignificantBits() ^ leaderboardId.getLeastSignificantBits();
    }
}
