package com.jake.realtimeapi.snapshots.application.command;

import java.util.UUID;

public record RecoverLeaderboardSnapshotCommand(
        UUID leaderboardId,
        int topN
) {
}
