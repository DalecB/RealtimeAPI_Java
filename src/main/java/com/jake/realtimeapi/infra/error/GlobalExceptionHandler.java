package com.jake.realtimeapi.infra.error;

import com.jake.realtimeapi.apikeys.domain.exception.ApiKeyExpiredException;
import com.jake.realtimeapi.apikeys.domain.exception.ApiKeyRevokedException;
import com.jake.realtimeapi.apikeys.domain.exception.DailyQuotaExceededException;
import com.jake.realtimeapi.apikeys.domain.exception.InvalidApiKeyException;
import com.jake.realtimeapi.apikeys.domain.exception.RateLimitExceededException;
import com.jake.realtimeapi.auth.domain.exception.AdminAuthenticationException;
import com.jake.realtimeapi.events.domain.exception.IdempotencyKeyReuseMismatchException;
import com.jake.realtimeapi.events.domain.exception.InvalidDeltaScoreException;
import com.jake.realtimeapi.leaderboards.domain.exception.LeaderboardAlreadyExistsException;
import com.jake.realtimeapi.leaderboards.domain.exception.LeaderboardNotFoundException;
import com.jake.realtimeapi.infra.circuitbreaker.RedisCircuitBreakerOpenException;
import com.jake.realtimeapi.projects.domain.exception.ProjectAlreadyExistsException;
import com.jake.realtimeapi.projects.domain.exception.ProjectAccessDeniedException;
import com.jake.realtimeapi.projects.domain.exception.ProjectNotFoundException;
import com.jake.realtimeapi.snapshots.domain.exception.SnapshotNotFoundException;
import com.jake.realtimeapi.support.api.ApiErrorResponse;
import com.jake.realtimeapi.users.domain.exception.UserAlreadyExistsException;
import com.jake.realtimeapi.users.domain.exception.UserNotFoundException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(AdminAuthenticationException.class)
    public ResponseEntity<ApiErrorResponse> handleAdminAuthentication(AdminAuthenticationException exception) {
        return build(HttpStatus.UNAUTHORIZED, "INVALID_ADMIN_AUTH", exception.getMessage());
    }

    @ExceptionHandler(InvalidApiKeyException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidApiKey(InvalidApiKeyException exception) {
        return build(HttpStatus.UNAUTHORIZED, "INVALID_API_KEY", exception.getMessage());
    }

    @ExceptionHandler(ApiKeyRevokedException.class)
    public ResponseEntity<ApiErrorResponse> handleApiKeyRevoked(ApiKeyRevokedException exception) {
        return build(HttpStatus.FORBIDDEN, "API_KEY_REVOKED", exception.getMessage());
    }

    @ExceptionHandler(ApiKeyExpiredException.class)
    public ResponseEntity<ApiErrorResponse> handleApiKeyExpired(ApiKeyExpiredException exception) {
        return build(HttpStatus.FORBIDDEN, "API_KEY_EXPIRED", exception.getMessage());
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ApiErrorResponse> handleRateLimitExceeded(RateLimitExceededException exception) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("X-RateLimit-Remaining", "0")
                .header("Retry-After", Long.toString(exception.getRetryAfterSeconds()))
                .body(ApiErrorResponse.of(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMIT_EXCEEDED", exception.getMessage()));
    }

    @ExceptionHandler(DailyQuotaExceededException.class)
    public ResponseEntity<ApiErrorResponse> handleDailyQuotaExceeded(DailyQuotaExceededException exception) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("X-RateLimit-Remaining", "0")
                .header("Retry-After", Long.toString(exception.getRetryAfterSeconds()))
                .body(ApiErrorResponse.of(HttpStatus.TOO_MANY_REQUESTS, "DAILY_QUOTA_EXCEEDED", exception.getMessage()));
    }

    @ExceptionHandler(RedisCircuitBreakerOpenException.class)
    public ResponseEntity<ApiErrorResponse> handleRedisCircuitBreakerOpen(RedisCircuitBreakerOpenException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", Long.toString(exception.getRetryAfterSeconds()))
                .body(ApiErrorResponse.of(HttpStatus.SERVICE_UNAVAILABLE, "CIRCUIT_BREAKER_OPEN", exception.getMessage()));
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleUserNotFound(UserNotFoundException exception) {
        return build(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(UserAlreadyExistsException.class)
    public ResponseEntity<ApiErrorResponse> handleUserAlreadyExists(UserAlreadyExistsException exception) {
        return build(HttpStatus.CONFLICT, "USER_ALREADY_EXISTS", exception.getMessage());
    }

    @ExceptionHandler(ProjectNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleProjectNotFound(ProjectNotFoundException exception) {
        return build(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(ProjectAlreadyExistsException.class)
    public ResponseEntity<ApiErrorResponse> handleProjectAlreadyExists(ProjectAlreadyExistsException exception) {
        return build(HttpStatus.CONFLICT, "PROJECT_ALREADY_EXISTS", exception.getMessage());
    }

    @ExceptionHandler(ProjectAccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> handleProjectAccessDenied(ProjectAccessDeniedException exception) {
        return build(HttpStatus.FORBIDDEN, "PROJECT_ACCESS_DENIED", exception.getMessage());
    }

    @ExceptionHandler(LeaderboardNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleLeaderboardNotFound(LeaderboardNotFoundException exception) {
        return build(HttpStatus.NOT_FOUND, "LEADERBOARD_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(LeaderboardAlreadyExistsException.class)
    public ResponseEntity<ApiErrorResponse> handleLeaderboardAlreadyExists(LeaderboardAlreadyExistsException exception) {
        return build(HttpStatus.CONFLICT, "LEADERBOARD_ALREADY_EXISTS", exception.getMessage());
    }

    @ExceptionHandler(SnapshotNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleSnapshotNotFound(SnapshotNotFoundException exception) {
        return build(HttpStatus.NOT_FOUND, "SNAPSHOT_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(IdempotencyKeyReuseMismatchException.class)
    public ResponseEntity<ApiErrorResponse> handleIdempotencyKeyReuseMismatch(IdempotencyKeyReuseMismatchException exception) {
        return build(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSE_MISMATCH", exception.getMessage());
    }

    @ExceptionHandler(InvalidDeltaScoreException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidDeltaScore(InvalidDeltaScoreException exception) {
        return build(HttpStatus.BAD_REQUEST, "INVALID_DELTA_SCORE", exception.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleMethodArgumentNotValid(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return build(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleConstraintViolation(ConstraintViolationException exception) {
        return build(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", exception.getMessage());
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiErrorResponse> handleMissingRequestHeader(MissingRequestHeaderException exception) {
        return build(
                HttpStatus.BAD_REQUEST,
                "INVALID_REQUEST",
                "Required header '" + exception.getHeaderName() + "' is missing"
        );
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> handleMethodArgumentTypeMismatch(MethodArgumentTypeMismatchException exception) {
        if (exception.getRequiredType() == java.util.UUID.class) {
            return build(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", normalizeUuidArgumentName(exception.getName()) + " must be a valid UUID");
        }
        return build(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", exception.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleIllegalArgument(IllegalArgumentException exception) {
        return build(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", exception.getMessage());
    }

    private ResponseEntity<ApiErrorResponse> build(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status)
                .body(ApiErrorResponse.of(status, code, message));
    }

    private String normalizeUuidArgumentName(String argumentName) {
        if ("Idempotency-Key".equals(argumentName)) {
            return "idempotencyKey";
        }
        return argumentName;
    }
}
