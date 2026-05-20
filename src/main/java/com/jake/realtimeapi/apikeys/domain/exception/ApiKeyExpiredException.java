package com.jake.realtimeapi.apikeys.domain.exception;

public class ApiKeyExpiredException extends RuntimeException {

    public ApiKeyExpiredException(String message) {
        super(message);
    }
}
