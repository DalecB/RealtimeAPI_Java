package com.jake.realtimeapi.snapshots.application.model;

import java.time.Instant;

public record SnapshotStatus(
        Instant lastSuccessfulSnapshotAt,
        long snapshotLagSeconds
) {
}
