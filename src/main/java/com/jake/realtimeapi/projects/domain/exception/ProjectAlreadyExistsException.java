package com.jake.realtimeapi.projects.domain.exception;

public class ProjectAlreadyExistsException extends RuntimeException {

    public ProjectAlreadyExistsException(String name) {
        super("Project already exists: name=" + name);
    }
}
