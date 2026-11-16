package com.jake.realtimeapi.users.application.command;

public record CreateUserCommand(String externalId) {

    public CreateUserCommand {
        if (externalId == null || externalId.isBlank()) {
            throw new IllegalArgumentException("externalId is required");
        }
    }
}
