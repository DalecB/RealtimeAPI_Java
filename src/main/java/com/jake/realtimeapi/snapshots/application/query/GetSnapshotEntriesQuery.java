package com.jake.realtimeapi.snapshots.application.query;

import java.time.Instant;
import java.util.UUID;

public record GetSnapshotEntriesQuery(
        UUID leaderboardId,
        Instant snapshotAt,
        int offset,
        int limit
) {
}
