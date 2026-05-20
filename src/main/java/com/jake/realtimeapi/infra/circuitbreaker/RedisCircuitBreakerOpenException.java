package com.jake.realtimeapi.infra.circuitbreaker;

public class RedisCircuitBreakerOpenException extends RuntimeException {

    private final long retryAfterSeconds;

    public RedisCircuitBreakerOpenException(long retryAfterSeconds) {
        super("Redis hot path is temporarily unavailable");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
