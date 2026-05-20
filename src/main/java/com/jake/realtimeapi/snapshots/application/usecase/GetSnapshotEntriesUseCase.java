package com.jake.realtimeapi.snapshots.application.usecase;

import com.jake.realtimeapi.snapshots.application.model.SnapshotEntriesResult;
import com.jake.realtimeapi.snapshots.application.query.GetSnapshotEntriesQuery;

public interface GetSnapshotEntriesUseCase {

    SnapshotEntriesResult getSnapshotEntries(GetSnapshotEntriesQuery query);
}
