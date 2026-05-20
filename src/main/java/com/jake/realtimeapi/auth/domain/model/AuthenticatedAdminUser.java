package com.jake.realtimeapi.auth.domain.model;

public record AuthenticatedAdminUser(
        long userId,
        String externalId
) {
}
