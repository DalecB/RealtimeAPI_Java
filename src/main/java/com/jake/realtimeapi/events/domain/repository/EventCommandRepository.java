package com.jake.realtimeapi.events.domain.repository;

import com.jake.realtimeapi.events.domain.model.EventPayload;
import com.jake.realtimeapi.events.domain.model.ProcessEventResult;

public interface EventCommandRepository {

    ProcessEventResult process(EventPayload payload);
}
