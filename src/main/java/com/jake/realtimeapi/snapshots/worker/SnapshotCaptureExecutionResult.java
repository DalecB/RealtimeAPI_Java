package com.jake.realtimeapi.snapshots.worker;

import com.jake.realtimeapi.snapshots.application.model.CaptureSnapshotResult;

public record SnapshotCaptureExecutionResult(
        boolean lockAcquired,
        CaptureSnapshotResult captureResult
) {
    public static SnapshotCaptureExecutionResult lockNotAcquired() {
        return new SnapshotCaptureExecutionResult(false, null);
    }

    public static SnapshotCaptureExecutionResult executed(CaptureSnapshotResult captureResult) {
        return new SnapshotCaptureExecutionResult(true, captureResult);
    }
}
