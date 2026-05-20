package com.jake.realtimeapi.snapshots.application.model;

import java.time.Instant;
import java.util.UUID;

public record RecoverLeaderboardSnapshotResult(
        UUID leaderboardId,
        boolean recovered,
        long restoredRowCount,
        Instant snapshotAt,
        String reason
) {
    public static RecoverLeaderboardSnapshotResult skipped(UUID leaderboardId, String reason) {
        return new RecoverLeaderboardSnapshotResult(leaderboardId, false, 0L, null, reason);
    }

    public static RecoverLeaderboardSnapshotResult recovered(UUID leaderboardId, long restoredRowCount, Instant snapshotAt) {
        return new RecoverLeaderboardSnapshotResult(leaderboardId, true, restoredRowCount, snapshotAt, "RECOVERED");
    }
}
