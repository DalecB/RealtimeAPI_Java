package com.jake.realtimeapi.projects.domain.exception;

import java.util.UUID;

public class ProjectAccessDeniedException extends RuntimeException {

    public ProjectAccessDeniedException(UUID projectId, Long adminId) {
        super("Project access denied: projectId=" + projectId + ", adminId=" + adminId);
    }
}
