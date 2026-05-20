package com.jake.realtimeapi.apikeys.domain.model;

public record RateLimitCheckResult(
        RateLimitDecision decision,
        int rateLimitRemaining,
        long retryAfterSeconds
) {

    public static RateLimitCheckResult allowed(int rateLimitRemaining) {
        return new RateLimitCheckResult(RateLimitDecision.ALLOWED, rateLimitRemaining, 0);
    }

    public static RateLimitCheckResult rateLimited(long retryAfterSeconds) {
        return new RateLimitCheckResult(RateLimitDecision.RATE_LIMIT_EXCEEDED, 0, retryAfterSeconds);
    }

    public static RateLimitCheckResult quotaExceeded(long retryAfterSeconds) {
        return new RateLimitCheckResult(RateLimitDecision.DAILY_QUOTA_EXCEEDED, 0, retryAfterSeconds);
    }
}
