package com.jake.realtimeapi.snapshots.application.usecase;

import com.jake.realtimeapi.snapshots.application.command.RecoverLeaderboardSnapshotCommand;
import com.jake.realtimeapi.snapshots.application.model.RecoverLeaderboardSnapshotResult;

public interface RecoverLeaderboardSnapshotUseCase {

    RecoverLeaderboardSnapshotResult recover(RecoverLeaderboardSnapshotCommand command);
}
