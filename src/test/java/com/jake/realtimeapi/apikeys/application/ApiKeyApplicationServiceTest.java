package com.jake.realtimeapi.apikeys.application;

import com.jake.realtimeapi.apikeys.application.command.CreateApiKeyCommand;
import com.jake.realtimeapi.apikeys.application.model.AuthorizedApiKeyResult;
import com.jake.realtimeapi.apikeys.application.model.IssuedApiKeyResult;
import com.jake.realtimeapi.apikeys.domain.exception.ApiKeyExpiredException;
import com.jake.realtimeapi.apikeys.domain.exception.ApiKeyRevokedException;
import com.jake.realtimeapi.apikeys.domain.exception.DailyQuotaExceededException;
import com.jake.realtimeapi.apikeys.domain.exception.InvalidApiKeyException;
import com.jake.realtimeapi.apikeys.domain.exception.RateLimitExceededException;
import com.jake.realtimeapi.apikeys.domain.model.ApiKey;
import com.jake.realtimeapi.apikeys.domain.model.ApiKeyStatus;
import com.jake.realtimeapi.apikeys.domain.model.RateLimitCheckResult;
import com.jake.realtimeapi.apikeys.domain.repository.ApiKeyRateLimitRepository;
import com.jake.realtimeapi.apikeys.domain.repository.ApiKeyRepository;
import com.jake.realtimeapi.infra.circuitbreaker.RedisCircuitBreakerOpenException;
import com.jake.realtimeapi.infra.circuitbreaker.RedisHotPathCircuitBreaker;
import com.jake.realtimeapi.infra.metrics.HotPathMetrics;
import com.jake.realtimeapi.projects.domain.model.Project;
import com.jake.realtimeapi.projects.domain.repository.ProjectRepository;
import com.jake.realtimeapi.usagestats.application.UsageStatsRecorder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiKeyApplicationServiceTest {

    @Mock
    private ApiKeyRepository apiKeyRepository;

    @Mock
    private ApiKeyRateLimitRepository apiKeyRateLimitRepository;

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private ApiKeySecretFactory apiKeySecretFactory;

    @Mock
    private RedisHotPathCircuitBreaker redisHotPathCircuitBreaker;

    @Mock
    private HotPathMetrics hotPathMetrics;

    @Mock
    private UsageStatsRecorder usageStatsRecorder;

    @InjectMocks
    private ApiKeyApplicationService apiKeyApplicationService;

    @Test
    void create_issuesAndPersistsApiKey() {
        UUID projectId = UUID.randomUUID();
        Instant expiresAt = Instant.parse("2026-03-20T00:00:00Z");
        Instant createdAt = Instant.parse("2026-03-19T00:00:00Z");

        when(projectRepository.findById(projectId)).thenReturn(Optional.of(new Project(projectId, 1L, "p", createdAt)));
        when(apiKeySecretFactory.generate()).thenReturn(new ApiKeySecretFactory.GeneratedApiKey("rk_secret", "rk_secret"));
        when(apiKeySecretFactory.hash("rk_secret")).thenReturn("hashed");
        when(apiKeyRepository.save(any(ApiKey.class))).thenReturn(new ApiKey(
                1L, projectId, "hashed", "rk_secret", ApiKeyStatus.ACTIVE, 100, 1000, createdAt, createdAt, null, expiresAt
        ));

        IssuedApiKeyResult result = apiKeyApplicationService.create(new CreateApiKeyCommand(projectId, 1L, 100, 1000, expiresAt));

        assertEquals(1L, result.id());
        assertEquals("rk_secret", result.rawKey());
        assertEquals("ACTIVE", result.status().name());
        verify(apiKeyRepository).save(any(ApiKey.class));
    }

    @Test
    void create_throwsWhenRequesterDoesNotOwnProject() {
        UUID projectId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-03-19T00:00:00Z");

        when(projectRepository.findById(projectId)).thenReturn(Optional.of(new Project(projectId, 2L, "p", createdAt)));

        assertThrows(RuntimeException.class, () -> apiKeyApplicationService.create(new CreateApiKeyCommand(projectId, 1L, 100, 1000, null)));
    }

    @Test
    void authorize_returnsRemainingLimitWhenApiKeyIsValid() {
        ApiKey apiKey = new ApiKey(
                1L, UUID.randomUUID(), "hashed", "rk_secret", ApiKeyStatus.ACTIVE, 100, 1000,
                Instant.now(), Instant.now(), null, Instant.now().plusSeconds(3600)
        );

        when(apiKeySecretFactory.hash("rk_secret")).thenReturn("hashed");
        when(apiKeyRepository.findByKeyHash("hashed")).thenReturn(Optional.of(apiKey));
        when(redisHotPathCircuitBreaker.execute(any()))
                .thenAnswer(invocation -> ((java.util.function.Supplier<?>) invocation.getArgument(0)).get());
        when(apiKeyRateLimitRepository.checkAndConsume(eq(1L), eq(100), eq(1000), any(Instant.class)))
                .thenReturn(RateLimitCheckResult.allowed(77));

        AuthorizedApiKeyResult result = apiKeyApplicationService.authorize("Bearer rk_secret");

        assertEquals(1L, result.apiKeyId());
        assertEquals(77, result.rateLimitRemaining());
    }

    @Test
    void authorize_throwsWhenAuthorizationHeaderIsInvalid() {
        assertThrows(InvalidApiKeyException.class, () -> apiKeyApplicationService.authorize("Basic nope"));
    }

    @Test
    void authorize_throwsWhenApiKeyIsRevoked() {
        ApiKey apiKey = new ApiKey(
                1L, UUID.randomUUID(), "hashed", "rk_secret", ApiKeyStatus.REVOKED, 100, 1000,
                Instant.now(), Instant.now(), Instant.now(), null
        );

        when(apiKeySecretFactory.hash("rk_secret")).thenReturn("hashed");
        when(apiKeyRepository.findByKeyHash("hashed")).thenReturn(Optional.of(apiKey));

        assertThrows(ApiKeyRevokedException.class, () -> apiKeyApplicationService.authorize("Bearer rk_secret"));
    }

    @Test
    void authorize_throwsWhenApiKeyIsExpired() {
        ApiKey apiKey = new ApiKey(
                1L, UUID.randomUUID(), "hashed", "rk_secret", ApiKeyStatus.ACTIVE, 100, 1000,
                Instant.now(), Instant.now(), null, Instant.now().minusSeconds(1)
        );

        when(apiKeySecretFactory.hash("rk_secret")).thenReturn("hashed");
        when(apiKeyRepository.findByKeyHash("hashed")).thenReturn(Optional.of(apiKey));

        assertThrows(ApiKeyExpiredException.class, () -> apiKeyApplicationService.authorize("Bearer rk_secret"));
    }

    @Test
    void authorize_throwsWhenRateLimitIsExceeded() {
        ApiKey apiKey = new ApiKey(
                1L, UUID.randomUUID(), "hashed", "rk_secret", ApiKeyStatus.ACTIVE, 100, 1000,
                Instant.now(), Instant.now(), null, Instant.now().plusSeconds(3600)
        );

        when(apiKeySecretFactory.hash("rk_secret")).thenReturn("hashed");
        when(apiKeyRepository.findByKeyHash("hashed")).thenReturn(Optional.of(apiKey));
        when(redisHotPathCircuitBreaker.execute(any()))
                .thenAnswer(invocation -> ((java.util.function.Supplier<?>) invocation.getArgument(0)).get());
        when(apiKeyRateLimitRepository.checkAndConsume(eq(1L), eq(100), eq(1000), any(Instant.class)))
                .thenReturn(RateLimitCheckResult.rateLimited(1));

        assertThrows(RateLimitExceededException.class, () -> apiKeyApplicationService.authorize("Bearer rk_secret"));
    }

    @Test
    void authorize_throwsWhenDailyQuotaIsExceeded() {
        ApiKey apiKey = new ApiKey(
                1L, UUID.randomUUID(), "hashed", "rk_secret", ApiKeyStatus.ACTIVE, 100, 1000,
                Instant.now(), Instant.now(), null, Instant.now().plusSeconds(3600)
        );

        when(apiKeySecretFactory.hash("rk_secret")).thenReturn("hashed");
        when(apiKeyRepository.findByKeyHash("hashed")).thenReturn(Optional.of(apiKey));
        when(redisHotPathCircuitBreaker.execute(any()))
                .thenAnswer(invocation -> ((java.util.function.Supplier<?>) invocation.getArgument(0)).get());
        when(apiKeyRateLimitRepository.checkAndConsume(eq(1L), eq(100), eq(1000), any(Instant.class)))
                .thenReturn(RateLimitCheckResult.quotaExceeded(10));

        assertThrows(DailyQuotaExceededException.class, () -> apiKeyApplicationService.authorize("Bearer rk_secret"));
    }

    @Test
    void authorize_throwsWhenCircuitBreakerIsOpenDuringRateLimitCheck() {
        ApiKey apiKey = new ApiKey(
                1L, UUID.randomUUID(), "hashed", "rk_secret", ApiKeyStatus.ACTIVE, 100, 1000,
                Instant.now(), Instant.now(), null, Instant.now().plusSeconds(3600)
        );

        when(apiKeySecretFactory.hash("rk_secret")).thenReturn("hashed");
        when(apiKeyRepository.findByKeyHash("hashed")).thenReturn(Optional.of(apiKey));
        when(redisHotPathCircuitBreaker.execute(any()))
                .thenThrow(new RedisCircuitBreakerOpenException(10));

        assertThrows(RedisCircuitBreakerOpenException.class, () -> apiKeyApplicationService.authorize("Bearer rk_secret"));
    }
}
