package com.jake.realtimeapi.snapshots.domain.repository;

public interface SnapshotExecutionLockRepository {

    // 동일 leaderboard snapshot 중복 실행을 방지하기 위한 분산 락 획득 시도.
    boolean tryLock(long lockKey);

    // 획득한 락을 해제한다. 락 미보유 상황은 구현체에서 안전하게 처리한다.
    void unlock(long lockKey);
}
