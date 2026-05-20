package com.jake.realtimeapi.snapshots.domain.model;

import java.time.Instant;
import java.util.UUID;

public record SnapshotBatches(
        Long id,
        UUID leaderboardId,
        Instant snapshotAt,
        Integer topN,
        Instant createdAt
) {

    public SnapshotBatches {
        if(leaderboardId == null) {
            throw new NullPointerException("leaderboardId is required");
        }
    }

    public static SnapshotBatches newSnapshotBatches(UUID leaderboardId, Integer topN) {
        return new SnapshotBatches(null, leaderboardId, null, topN, null);
    }
}
