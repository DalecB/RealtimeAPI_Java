package com.jake.realtimeapi.projects.domain.exception;

import java.util.UUID;

public class ProjectNotFoundException extends RuntimeException {

    public ProjectNotFoundException(UUID id) {
        super("Project not found: id=" + id);
    }

    public ProjectNotFoundException(String name) {
        super("Project not found: name=" + name);
    }
}
