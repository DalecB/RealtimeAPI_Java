package com.jake.realtimeapi.snapshots.persistence;

import com.jake.realtimeapi.snapshots.domain.model.SnapshotEntries;
import com.jake.realtimeapi.snapshots.persistence.entity.SnapshotEntriesJpaEntity;

public class SnapshotEntriesPersistenceMapper {

    private SnapshotEntriesPersistenceMapper() {}

    public static SnapshotEntries toDomain(SnapshotEntriesJpaEntity entity) {
        return new SnapshotEntries(entity.getId(), entity.getSnapshotId(), entity.getRank(), entity.getUserId(), entity.getScore(), entity.getCreatedAt());
    }

    public static SnapshotEntriesJpaEntity toEntity(SnapshotEntries snapshotEntries) {
        return new SnapshotEntriesJpaEntity(snapshotEntries.id(), snapshotEntries.snapshotId(), snapshotEntries.rank(), snapshotEntries.userId(), snapshotEntries.score(), snapshotEntries.createdAt());
    }


}
