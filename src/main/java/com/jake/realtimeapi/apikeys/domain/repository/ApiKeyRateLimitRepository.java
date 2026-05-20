package com.jake.realtimeapi.apikeys.domain.repository;

import com.jake.realtimeapi.apikeys.domain.model.RateLimitCheckResult;

import java.time.Instant;

public interface ApiKeyRateLimitRepository {

    RateLimitCheckResult checkAndConsume(long apiKeyId, int rateLimitPerSec, int dailyQuota, Instant now);
}
