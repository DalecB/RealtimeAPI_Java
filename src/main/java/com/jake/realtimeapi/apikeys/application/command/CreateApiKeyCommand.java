package com.jake.realtimeapi.apikeys.application.command;

import java.time.Instant;
import java.util.UUID;

public record CreateApiKeyCommand(
        UUID projectId,
        Long requesterAdminId,
        Integer rateLimitPerSec,
        Integer dailyQuota,
        Instant expiresAt
) {
}
