package com.jake.realtimeapi.users.domain.exception;

public class UserNotFoundException extends RuntimeException {

    public UserNotFoundException(Long id) {
        super("User not found: id=" + id);
    }

    public UserNotFoundException(String externalId) {
        super("User not found: externalId=" + externalId);
    }
}
