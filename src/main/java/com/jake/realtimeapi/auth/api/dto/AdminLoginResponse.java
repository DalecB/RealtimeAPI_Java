package com.jake.realtimeapi.auth.api.dto;

import java.time.Instant;

public record AdminLoginResponse(
        String accessToken,
        String tokenType,
        Instant expiresAt,
        long userId,
        String externalId
) {
}
