package com.jake.realtimeapi.snapshots.application.model;

import java.time.Instant;

public record SnapshotStatus(
        // 마지막으로 snapshot 저장이 성공한 시각. 성공 이력이 없으면 null.
        Instant lastSuccessfulSnapshotAt,
        // 마지막 성공 이후 경과 시간. 성공 이력이 없으면 -1.
        long snapshotLagSeconds
) {
}
