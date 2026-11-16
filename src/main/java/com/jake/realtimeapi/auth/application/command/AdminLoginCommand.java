package com.jake.realtimeapi.auth.application.command;

public record AdminLoginCommand(String externalId, String password) {

    public AdminLoginCommand {
        if (externalId == null || externalId.isBlank()) {
            throw new IllegalArgumentException("externalId is required");
        }
    }
}
