package com.jake.realtimeapi.snapshots.domain.repository;

public interface SnapshotExecutionLockRepository {

    boolean tryLockForCurrentTransaction(long lockKey);
}
