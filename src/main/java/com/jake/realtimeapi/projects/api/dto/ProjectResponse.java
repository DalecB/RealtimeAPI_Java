package com.jake.realtimeapi.projects.api.dto;

import java.time.Instant;
import java.util.UUID;

public record ProjectResponse(
        UUID id,
        Long adminId,
        String name,
        Instant createdAt
) {
}
