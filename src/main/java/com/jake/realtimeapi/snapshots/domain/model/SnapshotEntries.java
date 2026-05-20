package com.jake.realtimeapi.snapshots.domain.model;

import java.time.Instant;

public record SnapshotEntries(
        Long id,
        Long snapshotId,
        Integer rank,
        Long userId,
        Long score,
        Instant createdAt
) {

    public SnapshotEntries {
        if(snapshotId == null) {
            throw new NullPointerException("snapshotId is required");
        }
        if(userId == null) {
            throw new NullPointerException("userId is required");
        }
        if(score <= 0) {
            throw new NullPointerException("score must be positive");
        }
    }

    public static SnapshotEntries newSnapshotEntries(Long snapshotId,Integer rank, Long userId, Long score) {
        return new SnapshotEntries(null, snapshotId, rank, userId, score, null);
    }
}
