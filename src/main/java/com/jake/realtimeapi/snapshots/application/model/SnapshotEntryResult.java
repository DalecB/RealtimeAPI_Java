package com.jake.realtimeapi.snapshots.application.model;

public record SnapshotEntryResult(
        int rank,
        long userId,
        long score
) {
}
