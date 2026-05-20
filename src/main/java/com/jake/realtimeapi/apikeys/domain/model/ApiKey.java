package com.jake.realtimeapi.apikeys.domain.model;

import java.time.Instant;
import java.util.UUID;

public record ApiKey(
        Long id,
        UUID projectId,
        String keyHash,
        String keyPrefix,
        ApiKeyStatus status,
        int rateLimitPerSec,
        int dailyQuota,
        Instant createdAt,
        Instant updatedAt,
        Instant revokedAt,
        Instant expiresAt
) {

    public ApiKey {
        if (projectId == null) {
            throw new NullPointerException("projectId is required");
        }
        if (keyHash == null || keyHash.isBlank()) {
            throw new IllegalArgumentException("keyHash is required");
        }
        if (keyPrefix == null || keyPrefix.isBlank()) {
            throw new IllegalArgumentException("keyPrefix is required");
        }
        if (status == null) {
            throw new NullPointerException("status is required");
        }
        if (rateLimitPerSec <= 0) {
            throw new IllegalArgumentException("rateLimitPerSec must be positive");
        }
        if (dailyQuota <= 0) {
            throw new IllegalArgumentException("dailyQuota must be positive");
        }
    }
}
