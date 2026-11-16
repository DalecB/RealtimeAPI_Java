package com.jake.realtimeapi.snapshots.api.dto;

import java.time.Instant;

public record SnapshotStatusResponse(
        Instant lastSuccessfulSnapshotAt,
        long snapshotLagSeconds
) {
}
