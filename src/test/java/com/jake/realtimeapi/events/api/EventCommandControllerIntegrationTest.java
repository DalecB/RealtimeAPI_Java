package com.jake.realtimeapi.events.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jake.realtimeapi.apikeys.application.model.AuthorizedApiKeyResult;
import com.jake.realtimeapi.apikeys.application.usecase.AuthorizeEventApiKeyUseCase;
import com.jake.realtimeapi.apikeys.domain.exception.InvalidApiKeyException;
import com.jake.realtimeapi.apikeys.domain.exception.RateLimitExceededException;
import com.jake.realtimeapi.auth.application.usecase.AuthenticateAdminJwtUseCase;
import com.jake.realtimeapi.events.application.command.ProcessEventCommand;
import com.jake.realtimeapi.events.application.usecase.ProcessEventUseCase;
import com.jake.realtimeapi.events.domain.exception.IdempotencyKeyReuseMismatchException;
import com.jake.realtimeapi.events.domain.model.ProcessEventResult;
import com.jake.realtimeapi.infra.circuitbreaker.RedisCircuitBreakerOpenException;
import com.jake.realtimeapi.infra.error.GlobalExceptionHandler;
import com.jake.realtimeapi.usagestats.application.UsageStatsRecorder;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = EventCommandController.class)
@Import(GlobalExceptionHandler.class)
class EventCommandControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AuthorizeEventApiKeyUseCase authorizeEventApiKeyUseCase;

    @MockitoBean
    private ProcessEventUseCase processEventUseCase;

    @MockitoBean
    private AuthenticateAdminJwtUseCase authenticateAdminJwtUseCase;

    @MockitoBean
    private UsageStatsRecorder usageStatsRecorder;

    @Test
    void process_returns200AndMapsResponse() throws Exception {
        UUID leaderboardId = UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();
        Instant processedAt = Instant.parse("2026-03-11T01:02:03Z");

        when(authorizeEventApiKeyUseCase.authorize("Bearer test-key"))
                .thenReturn(new AuthorizedApiKeyResult(1L, 99));
        when(processEventUseCase.process(any(ProcessEventCommand.class)))
                .thenReturn(new ProcessEventResult(idempotencyKey, false, processedAt));

        Map<String, Object> body = new HashMap<>();
        body.put("leaderboardId", leaderboardId);
        body.put("userId", "1");
        body.put("deltaScore", 10);

        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer test-key")
                        .header("Idempotency-Key", idempotencyKey.toString())
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idempotencyKey").value(idempotencyKey.toString()))
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.processedAt").value(processedAt.toString()))
                .andExpect(result -> assertEquals("99", result.getResponse().getHeader("X-RateLimit-Remaining")));

        ArgumentCaptor<ProcessEventCommand> captor = ArgumentCaptor.forClass(ProcessEventCommand.class);
        verify(authorizeEventApiKeyUseCase).authorize("Bearer test-key");
        verify(processEventUseCase).process(captor.capture());
        ProcessEventCommand captured = captor.getValue();
        assertEquals(leaderboardId, captured.leaderboardId());
        assertEquals(1L, captured.userId());
        assertEquals(10L, captured.deltaScore());
        assertEquals(idempotencyKey, captured.idempotencyKey());
    }

    @Test
    void process_returns409WhenIdempotencyKeyReuseMismatch() throws Exception {
        UUID leaderboardId = UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();

        when(authorizeEventApiKeyUseCase.authorize("Bearer test-key"))
                .thenReturn(new AuthorizedApiKeyResult(1L, 99));
        when(processEventUseCase.process(any(ProcessEventCommand.class)))
                .thenThrow(new IdempotencyKeyReuseMismatchException(idempotencyKey));

        Map<String, Object> body = new HashMap<>();
        body.put("leaderboardId", leaderboardId);
        body.put("userId", "1");
        body.put("deltaScore", 10);

        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer test-key")
                        .header("Idempotency-Key", idempotencyKey.toString())
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSE_MISMATCH"));
    }

    @Test
    void process_returns400WhenRequestValidationFails() throws Exception {
        UUID leaderboardId = UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();

        when(authorizeEventApiKeyUseCase.authorize("Bearer test-key"))
                .thenReturn(new AuthorizedApiKeyResult(1L, 99));
        Map<String, Object> body = new HashMap<>();
        body.put("leaderboardId", leaderboardId);
        body.put("userId", "1");
        body.put("deltaScore", 0);

        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer test-key")
                        .header("Idempotency-Key", idempotencyKey.toString())
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void process_returns400WhenIdempotencyKeyHeaderIsMissing() throws Exception {
        UUID leaderboardId = UUID.randomUUID();
        when(authorizeEventApiKeyUseCase.authorize("Bearer test-key"))
                .thenReturn(new AuthorizedApiKeyResult(1L, 99));

        Map<String, Object> body = new HashMap<>();
        body.put("leaderboardId", leaderboardId);
        body.put("userId", "1");
        body.put("deltaScore", 10);

        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer test-key")
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("Required header 'Idempotency-Key' is missing"));

        verify(processEventUseCase, never()).process(any(ProcessEventCommand.class));
    }

    @Test
    void process_returns400WhenIdempotencyKeyHeaderIsNotUuid() throws Exception {
        UUID leaderboardId = UUID.randomUUID();

        when(authorizeEventApiKeyUseCase.authorize("Bearer test-key"))
                .thenReturn(new AuthorizedApiKeyResult(1L, 99));
        Map<String, Object> body = new HashMap<>();
        body.put("leaderboardId", leaderboardId);
        body.put("userId", "1");
        body.put("deltaScore", 10);

        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer test-key")
                        .header("Idempotency-Key", "not-a-uuid")
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("idempotencyKey must be a valid UUID"));

        verify(processEventUseCase, never()).process(any(ProcessEventCommand.class));
    }

    @Test
    void process_returns400WhenUserIdIsNotNumeric() throws Exception {
        UUID leaderboardId = UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();

        when(authorizeEventApiKeyUseCase.authorize("Bearer test-key"))
                .thenReturn(new AuthorizedApiKeyResult(1L, 99));
        Map<String, Object> body = new HashMap<>();
        body.put("leaderboardId", leaderboardId);
        body.put("userId", "user-1");
        body.put("deltaScore", 10);

        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer test-key")
                        .header("Idempotency-Key", idempotencyKey.toString())
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"))
                .andExpect(jsonPath("$.message").value("userId must be a positive integer"));

        verify(processEventUseCase, never()).process(any(ProcessEventCommand.class));
    }

    @Test
    void process_returns401WhenAuthorizationHeaderIsMissingOrInvalid() throws Exception {
        UUID leaderboardId = UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();

        when(authorizeEventApiKeyUseCase.authorize(null))
                .thenThrow(new InvalidApiKeyException("Authorization header must use Bearer token"));

        Map<String, Object> body = new HashMap<>();
        body.put("leaderboardId", leaderboardId);
        body.put("userId", "1");
        body.put("deltaScore", 10);

        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", idempotencyKey.toString())
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_API_KEY"));

        verify(processEventUseCase, never()).process(any(ProcessEventCommand.class));
    }

    @Test
    void process_returns429WhenRateLimitIsExceeded() throws Exception {
        UUID leaderboardId = UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();

        when(authorizeEventApiKeyUseCase.authorize("Bearer test-key"))
                .thenThrow(new RateLimitExceededException(1));

        Map<String, Object> body = new HashMap<>();
        body.put("leaderboardId", leaderboardId);
        body.put("userId", "1");
        body.put("deltaScore", 10);

        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer test-key")
                        .header("Idempotency-Key", idempotencyKey.toString())
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"))
                .andExpect(result -> assertEquals("0", result.getResponse().getHeader("X-RateLimit-Remaining")))
                .andExpect(result -> assertEquals("1", result.getResponse().getHeader("Retry-After")));

        verify(processEventUseCase, never()).process(any(ProcessEventCommand.class));
    }

    @Test
    void process_returns503WhenRedisCircuitBreakerIsOpen() throws Exception {
        UUID leaderboardId = UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();

        when(authorizeEventApiKeyUseCase.authorize("Bearer test-key"))
                .thenReturn(new AuthorizedApiKeyResult(1L, 99));
        when(processEventUseCase.process(any(ProcessEventCommand.class)))
                .thenThrow(new RedisCircuitBreakerOpenException(10));

        Map<String, Object> body = new HashMap<>();
        body.put("leaderboardId", leaderboardId);
        body.put("userId", "1");
        body.put("deltaScore", 10);

        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer test-key")
                        .header("Idempotency-Key", idempotencyKey.toString())
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("CIRCUIT_BREAKER_OPEN"))
                .andExpect(result -> assertEquals("10", result.getResponse().getHeader("Retry-After")));
    }
}
