package com.jake.realtimeapi.apikeys.domain.exception;

public class DailyQuotaExceededException extends RuntimeException {

    private final long retryAfterSeconds;

    public DailyQuotaExceededException(long retryAfterSeconds) {
        super("daily quota exceeded");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
