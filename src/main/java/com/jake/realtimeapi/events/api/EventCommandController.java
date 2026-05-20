package com.jake.realtimeapi.events.api;

import com.jake.realtimeapi.apikeys.application.model.AuthorizedApiKeyResult;
import com.jake.realtimeapi.apikeys.application.usecase.AuthorizeEventApiKeyUseCase;
import com.jake.realtimeapi.events.api.dto.ProcessEventRequest;
import com.jake.realtimeapi.events.api.dto.ProcessEventResponse;
import com.jake.realtimeapi.events.application.command.ProcessEventCommand;
import com.jake.realtimeapi.events.application.usecase.ProcessEventUseCase;
import com.jake.realtimeapi.events.domain.exception.IdempotencyKeyReuseMismatchException;
import com.jake.realtimeapi.events.domain.model.ProcessEventResult;
import com.jake.realtimeapi.infra.circuitbreaker.RedisCircuitBreakerOpenException;
import com.jake.realtimeapi.support.userid.UserIdCodec;
import com.jake.realtimeapi.usagestats.application.UsageStatsRecorder;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/events")
public class EventCommandController {

    private final AuthorizeEventApiKeyUseCase authorizeEventApiKeyUseCase;
    private final ProcessEventUseCase processEventUseCase;
    private final UsageStatsRecorder usageStatsRecorder;

    public EventCommandController(
            AuthorizeEventApiKeyUseCase authorizeEventApiKeyUseCase,
            ProcessEventUseCase processEventUseCase,
            UsageStatsRecorder usageStatsRecorder
    ) {
        this.authorizeEventApiKeyUseCase = authorizeEventApiKeyUseCase;
        this.processEventUseCase = processEventUseCase;
        this.usageStatsRecorder = usageStatsRecorder;
    }

    @PostMapping
    public ResponseEntity<ProcessEventResponse> process(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @Valid @RequestBody ProcessEventRequest request
    ) {
        AuthorizedApiKeyResult authorizedApiKey = authorizeEventApiKeyUseCase.authorize(authorization);

        try {
            ProcessEventResult result = processEventUseCase.process(
                    new ProcessEventCommand(
                            request.leaderboardId(),
                            UserIdCodec.parse(request.userId()),
                            request.deltaScore(),
                            idempotencyKey
                    )
            );

            usageStatsRecorder.recordProcessed(authorizedApiKey.apiKeyId(), result.replayed(), result.processedAt());

            return ResponseEntity.ok()
                    .header("X-RateLimit-Remaining", Integer.toString(authorizedApiKey.rateLimitRemaining()))
                    .body(new ProcessEventResponse(
                            result.idempotencyKey(),
                            result.replayed(),
                            result.processedAt()
                    ));

        } catch (IdempotencyKeyReuseMismatchException exception) {
            usageStatsRecorder.recordIdempotencyConflict(authorizedApiKey.apiKeyId(), java.time.Instant.now());
            throw exception;
        } catch (RedisCircuitBreakerOpenException exception) {
            usageStatsRecorder.recordBlocked(authorizedApiKey.apiKeyId(), java.time.Instant.now());
            throw exception;
        }
    }
}
