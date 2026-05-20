package com.jake.realtimeapi.events.domain.exception;

import java.util.UUID;

public class IdempotencyKeyReuseMismatchException extends RuntimeException {
    public IdempotencyKeyReuseMismatchException(UUID idempotencyKey) {
        super("Idempotency key reuse mismatch: " + idempotencyKey);
    }
}
