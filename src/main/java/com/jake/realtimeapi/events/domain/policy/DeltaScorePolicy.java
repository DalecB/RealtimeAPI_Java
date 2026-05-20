package com.jake.realtimeapi.events.domain.policy;

import com.jake.realtimeapi.events.domain.exception.InvalidDeltaScoreException;
import org.springframework.stereotype.Component;

@Component
public class DeltaScorePolicy {

    public void validate(long deltaScore) {

        if(deltaScore <= 0) {
            throw new InvalidDeltaScoreException(deltaScore);
        }
    }
}
