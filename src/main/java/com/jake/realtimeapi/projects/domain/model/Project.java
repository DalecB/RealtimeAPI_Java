package com.jake.realtimeapi.projects.domain.model;

import java.time.Instant;
import java.util.UUID;

public record Project(
        UUID id,
        Long adminId,
        String name,
        Instant createdAt
) {

    private static final int MAX_NAME_LENGTH = 100;

    public Project {
        if(name == null || name.isBlank()) {
            throw new NullPointerException("name is required");
        }
        if(name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("name length must be <= " + MAX_NAME_LENGTH);
        }
    }

    public static Project newProject(String name, Long adminId) {
        if (adminId == null || adminId <= 0) {
            throw new IllegalArgumentException("adminId is required");
        }
        return new Project(null, adminId, name, null);
    }
}
