package com.jake.realtimeapi.snapshots.application.model;

import java.time.Instant;
import java.util.UUID;

public record CaptureSnapshotResult(
        UUID leaderboardId,
        Instant snapshotAt,
        int topN,
        Long snapshotId,
        int rowCount,
        boolean skipped
) {
    public static CaptureSnapshotResult skipped(UUID leaderboardId, Instant snapshotAt, int topN) {
        return new CaptureSnapshotResult(leaderboardId, snapshotAt, topN, null, 0, true);
    }

    public static CaptureSnapshotResult captured(
            UUID leaderboardId,
            Instant snapshotAt,
            int topN,
            long snapshotId,
            int rowCount
    ) {
        return new CaptureSnapshotResult(leaderboardId, snapshotAt, topN, snapshotId, rowCount, false);
    }
}
