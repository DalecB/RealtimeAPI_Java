package com.jake.realtimeapi.snapshots.domain.repository;

import com.jake.realtimeapi.snapshots.domain.model.SnapshotBatches;
import com.jake.realtimeapi.snapshots.domain.repository.command.SnapshotBatchUpsertParam;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SnapshotBatchesRepository {

    long upsert(SnapshotBatchUpsertParam param);

    Optional<SnapshotBatches> findById(long id);

    Optional<SnapshotBatches> findLatestByLeaderboardId(UUID leaderboardId);

    List<SnapshotBatches> findByLeaderboardIdOrderBySnapshotAtDesc(UUID leaderboardId);

    Optional<SnapshotBatches> findByLeaderboardIdAndSnapshotAt(UUID leaderboardId, Instant snapshotAt);
}
