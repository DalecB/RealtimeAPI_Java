package com.jake.realtimeapi.snapshots.domain.model;

public record SnapshotRankingRow(
        long userId,
        int rank,
        long score
) {
}
