package com.jake.realtimeapi.users.api.dto;

import java.time.Instant;

public record UserResponse(
        Long id,
        String externalId,
        Instant createdAt
) {
}
