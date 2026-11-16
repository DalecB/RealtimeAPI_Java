package com.jake.realtimeapi.snapshots.application.model;

import java.time.Instant;
import java.util.UUID;

public record CaptureSnapshotResult(
        UUID leaderboardId,
        Instant snapshotAt,
        int topN,
        Long snapshotId,
        int rowCount,
        boolean skipped,
        // EMPTY(Empty Guard) | SHRUNK(Shrink Guard). 저장했으면 null
        String skipReason
) {
    public static CaptureSnapshotResult skipped(UUID leaderboardId, Instant snapshotAt, int topN) {
        return new CaptureSnapshotResult(leaderboardId, snapshotAt, topN, null, 0, true, "EMPTY");
    }

    // Shrink Guard: 참여자는 줄지 않으므로, 직전 스냅샷보다 행이 적으면 Redis가 데이터를 잃은 것으로 보고 저장하지 않는다.
    public static CaptureSnapshotResult shrunk(UUID leaderboardId, Instant snapshotAt, int topN, int rowCount) {
        return new CaptureSnapshotResult(leaderboardId, snapshotAt, topN, null, rowCount, true, "SHRUNK");
    }

    public static CaptureSnapshotResult captured(
            UUID leaderboardId,
            Instant snapshotAt,
            int topN,
            long snapshotId,
            int rowCount
    ) {
        return new CaptureSnapshotResult(leaderboardId, snapshotAt, topN, snapshotId, rowCount, false, null);
    }
}
