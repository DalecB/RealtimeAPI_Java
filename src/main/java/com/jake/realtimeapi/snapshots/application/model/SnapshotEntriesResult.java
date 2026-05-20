package com.jake.realtimeapi.snapshots.application.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SnapshotEntriesResult(
        UUID leaderboardId,
        long snapshotId,
        Instant snapshotAt,
        int topN,
        long total,
        List<SnapshotEntryResult> items
) {
}
