package com.jake.realtimeapi.snapshots.domain.repository;

public interface SnapshotExecutionLockRepository {

    boolean tryLock(long lockKey);

    void unlock(long lockKey);
}
