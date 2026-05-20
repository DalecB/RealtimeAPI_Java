package com.jake.realtimeapi.snapshots.worker;

import com.jake.realtimeapi.snapshots.application.model.CaptureSnapshotResult;

public record SnapshotCaptureExecutionResult(
        boolean lockAcquired,
        CaptureSnapshotResult captureResult
) {
    // 락 획득 실패: 실제 capture 로직은 실행되지 않았다.
    public static SnapshotCaptureExecutionResult lockNotAcquired() {
        return new SnapshotCaptureExecutionResult(false, null);
    }

    // 락 획득 후 capture가 정상 실행된 결과.
    public static SnapshotCaptureExecutionResult executed(CaptureSnapshotResult captureResult) {
        return new SnapshotCaptureExecutionResult(true, captureResult);
    }
}
