package com.jake.realtimeapi.snapshots.application.command;

import java.time.Instant;
import java.util.UUID;

public record CaptureSnapshotCommand(
        UUID leaderboardId,
        Instant snapshotAt,
        int topN
) {
    public CaptureSnapshotCommand {
        // 캡처 실행 단위에 필요한 최소 입력값을 command로 고정한다.
        if (leaderboardId == null) {
            throw new IllegalArgumentException("leaderboardId is required");
        }
        if (snapshotAt == null) {
            throw new IllegalArgumentException("snapshotAt is required");
        }
        if (topN <= 0 || topN > 1_000) {
            throw new IllegalArgumentException("topN must be between 1 and 1000, but was " + topN);
        }
    }
}
