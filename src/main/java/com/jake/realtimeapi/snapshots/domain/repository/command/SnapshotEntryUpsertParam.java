package com.jake.realtimeapi.snapshots.domain.repository.command;

public record SnapshotEntryUpsertParam(
        long snapshotId,
        long userId,
        int rank,
        long score
) { }