package com.jake.realtimeapi.events.api;

import com.jake.realtimeapi.events.api.dto.StreamsStatusResponse;
import com.jake.realtimeapi.events.application.usecase.GetStreamsStatusUseCase;
import com.jake.realtimeapi.events.domain.model.StreamsStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/streams")
public class InternalStreamsStatusController {

    private final GetStreamsStatusUseCase getStreamsStatusUseCase;

    public InternalStreamsStatusController(GetStreamsStatusUseCase getStreamsStatusUseCase) {
        this.getStreamsStatusUseCase = getStreamsStatusUseCase;
    }

    @GetMapping("/status")
    public StreamsStatusResponse getStatus() {
        StreamsStatus status = getStreamsStatusUseCase.getStatus();
        return new StreamsStatusResponse(status.pendingEntries(), status.consumerLag(), status.lastDeliveredId());
    }
}
