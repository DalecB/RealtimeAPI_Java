package com.jake.realtimeapi.snapshots.persistence;

import com.jake.realtimeapi.snapshots.domain.model.SnapshotBatches;
import com.jake.realtimeapi.snapshots.persistence.entity.SnapshotBatchesJpaEntity;

public class SnapshotBatchesPersistenceMapper {

    private SnapshotBatchesPersistenceMapper() {}

    public static SnapshotBatches toDomain(SnapshotBatchesJpaEntity entity) {
        return new SnapshotBatches(entity.getId(), entity.getLeaderboardId(), entity.getSnapshotAt(), entity.getTopN(), entity.getCreatedAt());
    }

    public static SnapshotBatchesJpaEntity toEntity(SnapshotBatches snapshotBatches) {
        return new SnapshotBatchesJpaEntity(snapshotBatches.id(), snapshotBatches.leaderboardId(), snapshotBatches.snapshotAt(), snapshotBatches.topN(), snapshotBatches.createdAt());
    }
}
