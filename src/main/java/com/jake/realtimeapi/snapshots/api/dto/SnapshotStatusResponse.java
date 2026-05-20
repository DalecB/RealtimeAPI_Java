package com.jake.realtimeapi.snapshots.api.dto;

import java.time.Instant;

public record SnapshotStatusResponse(
        // 마지막 성공 시각. 아직 성공이 없으면 null.
        Instant lastSuccessfulSnapshotAt,
        // 마지막 성공 이후 경과 초.
        long snapshotLagSeconds
) {
}
