package com.jake.realtimeapi.events.domain.exception;

public class InvalidDeltaScoreException extends RuntimeException {

    public InvalidDeltaScoreException(long deltaScore) {
        super("Delta score must be >= 1, but was " + deltaScore);
    }
}
