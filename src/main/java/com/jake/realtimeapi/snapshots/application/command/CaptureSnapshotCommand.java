package com.jake.realtimeapi.snapshots.application.command;

import java.time.Instant;
import java.util.UUID;

public record CaptureSnapshotCommand(
        UUID leaderboardId,
        Instant snapshotAt,
        int topN
) {
    public CaptureSnapshotCommand {
        if (leaderboardId == null) {
            throw new IllegalArgumentException("leaderboardId is required");
        }
        if (snapshotAt == null) {
            throw new IllegalArgumentException("snapshotAt is required");
        }
        if (topN <= 0 || topN > 1_000) {
            throw new IllegalArgumentException("topN must be between 1 and 1000, but was " + topN);
        }
    }
}
