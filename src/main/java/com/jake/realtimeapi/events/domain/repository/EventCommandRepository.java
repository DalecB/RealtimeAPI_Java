package com.jake.realtimeapi.events.domain.repository;

import com.jake.realtimeapi.events.domain.model.EventPayload;
import com.jake.realtimeapi.events.domain.model.ProcessEventResult;

public interface EventCommandRepository {

    // apiKeyId는 점수 계산에 쓰이지 않고 감사 기록에만 남으므로 EventPayload가 아니라 별도 인자로 받는다.
    // payload에 넣으면 멱등 해시(userId:deltaScore) 대상인지 아닌지가 한 record 안에서 섞인다.
    ProcessEventResult process(EventPayload payload, long apiKeyId);
}
