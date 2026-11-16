package com.jake.realtimeapi.events.domain.repository;

import com.jake.realtimeapi.events.domain.model.EventPayload;
import com.jake.realtimeapi.events.domain.model.ProcessEventResult;

public interface EventCommandRepository {

    // apiKeyId는 멱등 해시(userId:deltaScore) 대상이 아니라서 EventPayload에 넣지 않고 따로 받는다.
    ProcessEventResult process(EventPayload payload, long apiKeyId);
}
