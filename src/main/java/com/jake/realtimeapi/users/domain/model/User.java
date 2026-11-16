package com.jake.realtimeapi.users.domain.model;

import java.time.Instant;

public record User(Long id, String externalId, Instant createdAt) {

    private static final int MAX_EXTERNAL_ID_LENGTH = 30;

    public User {
        if (id != null && id <= 0) {
            throw new IllegalArgumentException("id must be positive");
        }
        if (externalId == null || externalId.isBlank()) {
            throw new IllegalArgumentException("externalId is required");
        }
        if (externalId.length() > MAX_EXTERNAL_ID_LENGTH) {
            throw new IllegalArgumentException("externalId length must be <= " + MAX_EXTERNAL_ID_LENGTH);
        }
    }

    public static User newUser(String externalId) {
        return new User(null, externalId, null);
    }
}
