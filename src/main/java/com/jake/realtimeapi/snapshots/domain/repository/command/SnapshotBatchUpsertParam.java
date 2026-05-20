package com.jake.realtimeapi.snapshots.domain.repository.command;

import java.time.Instant;
import java.util.UUID;

public record SnapshotBatchUpsertParam(
        UUID leaderboardId,
        Instant snapshotAt,
        int topN
) {
}
