package com.jake.realtimeapi.support.api;

import java.time.Instant;
import org.springframework.http.HttpStatus;

public record ApiErrorResponse(
        Instant timestamp,
        int status,
        String code,
        String message
) {

    public static ApiErrorResponse of(HttpStatus status, String code, String message) {
        return new ApiErrorResponse(Instant.now(), status.value(), code, message);
    }
}
