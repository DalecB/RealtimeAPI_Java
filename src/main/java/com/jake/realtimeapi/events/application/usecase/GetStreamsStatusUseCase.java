package com.jake.realtimeapi.events.application.usecase;

import com.jake.realtimeapi.events.domain.model.StreamsStatus;

public interface GetStreamsStatusUseCase {

    StreamsStatus getStatus();
}
