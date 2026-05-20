package com.jake.realtimeapi.apikeys.api.dto;

import java.time.Instant;
import java.util.UUID;

public record ApiKeyResponse(
        long id,
        UUID projectId,
        String rawKey,
        String keyPrefix,
        String status,
        int rateLimitPerSec,
        int dailyQuota,
        Instant createdAt,
        Instant expiresAt
) {
}
