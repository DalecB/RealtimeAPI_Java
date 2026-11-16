package com.jake.realtimeapi.users.domain.exception;

public class UserAlreadyExistsException extends RuntimeException {

    public UserAlreadyExistsException(String externalId) {
        super("User already exists: externalId=" + externalId);
    }
}
