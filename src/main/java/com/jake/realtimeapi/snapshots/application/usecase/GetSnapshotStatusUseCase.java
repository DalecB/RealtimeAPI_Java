package com.jake.realtimeapi.snapshots.application.usecase;

import com.jake.realtimeapi.snapshots.application.model.SnapshotStatus;

public interface GetSnapshotStatusUseCase {

    // worker가 기록한 snapshot 상태를 내부 운영 API에서 조회할 때 사용한다.
    SnapshotStatus getStatus();
}
