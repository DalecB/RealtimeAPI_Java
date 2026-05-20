package com.jake.realtimeapi.snapshots.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SnapshotEntriesResponse(
        UUID leaderboardId,
        long snapshotId,
        Instant snapshotAt,
        int topN,
        long total,
        List<SnapshotEntryItemResponse> items
) {
    public record SnapshotEntryItemResponse(
            int rank,
            String userId,
            long score
    ) {
    }
}
