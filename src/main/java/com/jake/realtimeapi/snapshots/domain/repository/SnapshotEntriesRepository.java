package com.jake.realtimeapi.snapshots.domain.repository;

import com.jake.realtimeapi.snapshots.domain.model.SnapshotEntries;
import com.jake.realtimeapi.snapshots.domain.repository.command.SnapshotEntryUpsertParam;

import java.util.List;
import java.util.Optional;

public interface SnapshotEntriesRepository {

    void replaceBySnapshotId(long snapshotId, List<SnapshotEntryUpsertParam> entries);

    long countBySnapshotId(long snapshotId);

    Optional<SnapshotEntries> findById(long id);

    Optional<SnapshotEntries> findBySnapshotIdAndUserId(long snapshotId, long userId);

    List<SnapshotEntries> findBySnapshotId(long snapshotId, int offset, int limit);
}
