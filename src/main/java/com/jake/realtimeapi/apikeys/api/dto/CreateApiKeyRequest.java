package com.jake.realtimeapi.apikeys.api.dto;

import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.UUID;

public record CreateApiKeyRequest(
        @NotNull UUID projectId,
        Integer rateLimitPerSec,
        Integer dailyQuota,
        Instant expiresAt
) {
}
