package com.jake.realtimeapi.auth.domain.model;

public record AdminAccount(
        long userId,
        String externalId,
        String passwordHash
) {
}
