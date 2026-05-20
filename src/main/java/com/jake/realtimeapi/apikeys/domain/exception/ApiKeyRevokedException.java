package com.jake.realtimeapi.apikeys.domain.exception;

public class ApiKeyRevokedException extends RuntimeException {

    public ApiKeyRevokedException(String message) {
        super(message);
    }
}
