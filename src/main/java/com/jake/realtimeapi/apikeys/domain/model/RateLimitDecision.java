package com.jake.realtimeapi.apikeys.domain.model;

public enum RateLimitDecision {
    ALLOWED,
    RATE_LIMIT_EXCEEDED,
    DAILY_QUOTA_EXCEEDED
}
