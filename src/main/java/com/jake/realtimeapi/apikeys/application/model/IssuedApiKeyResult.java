package com.jake.realtimeapi.apikeys.application.model;

import com.jake.realtimeapi.apikeys.domain.model.ApiKeyStatus;

import java.time.Instant;
import java.util.UUID;

public record IssuedApiKeyResult(
        long id,
        UUID projectId,
        String rawKey,
        String keyPrefix,
        ApiKeyStatus status,
        int rateLimitPerSec,
        int dailyQuota,
        Instant createdAt,
        Instant expiresAt
) {
}
