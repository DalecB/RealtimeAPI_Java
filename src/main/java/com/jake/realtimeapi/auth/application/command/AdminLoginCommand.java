package com.jake.realtimeapi.auth.application.command;

public record AdminLoginCommand(String externalId) {

    public AdminLoginCommand {
        if (externalId == null || externalId.isBlank()) {
            throw new IllegalArgumentException("externalId is required");
        }
    }
}
