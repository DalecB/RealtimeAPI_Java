package com.jake.realtimeapi.auth.application.model;

import java.time.Instant;

public record AdminLoginResult(
        String accessToken,
        String tokenType,
        Instant expiresAt,
        long userId,
        String externalId
) {
}
