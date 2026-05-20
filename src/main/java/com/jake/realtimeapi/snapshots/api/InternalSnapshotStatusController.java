package com.jake.realtimeapi.snapshots.api;

import com.jake.realtimeapi.snapshots.api.dto.SnapshotStatusResponse;
import com.jake.realtimeapi.snapshots.application.model.SnapshotStatus;
import com.jake.realtimeapi.snapshots.application.usecase.GetSnapshotStatusUseCase;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/snapshot")
public class InternalSnapshotStatusController {

    private final GetSnapshotStatusUseCase getSnapshotStatusUseCase;

    public InternalSnapshotStatusController(GetSnapshotStatusUseCase getSnapshotStatusUseCase) {
        this.getSnapshotStatusUseCase = getSnapshotStatusUseCase;
    }

    @GetMapping("/status")
    public SnapshotStatusResponse getStatus() {
        // 내부 운영 API: worker 메모리 상태를 외부에서 확인할 수 있게 노출한다.
        SnapshotStatus status = getSnapshotStatusUseCase.getStatus();
        return new SnapshotStatusResponse(status.lastSuccessfulSnapshotAt(), status.snapshotLagSeconds());
    }
}
