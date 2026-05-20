package com.jake.realtimeapi.apikeys.domain.exception;

public class InvalidApiKeyException extends RuntimeException {

    public InvalidApiKeyException(String message) {
        super(message);
    }
}
