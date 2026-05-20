package com.jake.realtimeapi.snapshots.application.usecase;

import com.jake.realtimeapi.snapshots.application.command.CaptureSnapshotCommand;
import com.jake.realtimeapi.snapshots.application.model.CaptureSnapshotResult;

public interface CaptureSnapshotUseCase {

    CaptureSnapshotResult capture(CaptureSnapshotCommand command);
}
