package com.jake.realtimeapi.projects.api.dto;

import java.time.Instant;
import java.util.UUID;

public record CreatedProjectResponse(
        UUID id,
        Long adminId,
        String name,
        Instant createdAt,
        DefaultApiKeyResponse defaultApiKey
) {
    public record DefaultApiKeyResponse(
            long id,
            String rawKey,
            String keyPrefix,
            String status,
            int rateLimitPerSec,
            int dailyQuota,
            Instant createdAt,
            Instant expiresAt
    ) {
    }
}
