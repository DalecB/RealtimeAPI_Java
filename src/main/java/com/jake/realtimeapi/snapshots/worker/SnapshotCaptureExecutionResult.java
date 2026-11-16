package com.jake.realtimeapi.snapshots.worker;

import com.jake.realtimeapi.snapshots.application.model.CaptureSnapshotResult;
import com.jake.realtimeapi.snapshots.application.model.RecoverLeaderboardSnapshotResult;

public record SnapshotCaptureExecutionResult(
        boolean lockAcquired,
        // 캡처 전에 한 cold 확인·복구 결과. 락을 못 잡았으면 null
        RecoverLeaderboardSnapshotResult recoveryResult,
        CaptureSnapshotResult captureResult
) {
    public static SnapshotCaptureExecutionResult lockNotAcquired() {
        return new SnapshotCaptureExecutionResult(false, null, null);
    }

    public static SnapshotCaptureExecutionResult executed(
            RecoverLeaderboardSnapshotResult recoveryResult,
            CaptureSnapshotResult captureResult
    ) {
        return new SnapshotCaptureExecutionResult(true, recoveryResult, captureResult);
    }
}
