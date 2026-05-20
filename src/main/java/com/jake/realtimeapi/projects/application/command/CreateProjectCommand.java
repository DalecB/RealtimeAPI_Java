package com.jake.realtimeapi.projects.application.command;

public record CreateProjectCommand(String name, Long adminId) {

    public CreateProjectCommand {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        if (adminId == null || adminId <= 0) {
            throw new IllegalArgumentException("adminId is required");
        }
    }
}
