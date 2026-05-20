package com.jake.realtimeapi.apikeys.application;

import com.jake.realtimeapi.apikeys.application.command.CreateApiKeyCommand;
import com.jake.realtimeapi.apikeys.application.model.AuthorizedApiKeyResult;
import com.jake.realtimeapi.apikeys.application.model.IssuedApiKeyResult;
import com.jake.realtimeapi.apikeys.application.usecase.AuthorizeEventApiKeyUseCase;
import com.jake.realtimeapi.apikeys.application.usecase.CreateApiKeyUseCase;
import com.jake.realtimeapi.apikeys.domain.exception.*;
import com.jake.realtimeapi.apikeys.domain.model.ApiKey;
import com.jake.realtimeapi.apikeys.domain.model.ApiKeyStatus;
import com.jake.realtimeapi.apikeys.domain.model.RateLimitCheckResult;
import com.jake.realtimeapi.apikeys.domain.model.RateLimitDecision;
import com.jake.realtimeapi.apikeys.domain.repository.ApiKeyRateLimitRepository;
import com.jake.realtimeapi.apikeys.domain.repository.ApiKeyRepository;
import com.jake.realtimeapi.infra.circuitbreaker.RedisHotPathCircuitBreaker;
import com.jake.realtimeapi.infra.metrics.HotPathMetrics;
import com.jake.realtimeapi.projects.domain.exception.ProjectAccessDeniedException;
import com.jake.realtimeapi.projects.domain.exception.ProjectNotFoundException;
import com.jake.realtimeapi.projects.domain.model.Project;
import com.jake.realtimeapi.projects.domain.repository.ProjectRepository;
import com.jake.realtimeapi.usagestats.application.UsageStatsRecorder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class ApiKeyApplicationService implements CreateApiKeyUseCase, AuthorizeEventApiKeyUseCase {

    private static final int DEFAULT_RATE_LIMIT_PER_SEC = 100;
    private static final int DEFAULT_DAILY_QUOTA = 1_000_000;
    private static final String BEARER_PREFIX = "Bearer ";

    private final ApiKeyRepository apiKeyRepository;
    private final ApiKeyRateLimitRepository apiKeyRateLimitRepository;
    private final ProjectRepository projectRepository;
    private final ApiKeySecretFactory apiKeySecretFactory;
    private final RedisHotPathCircuitBreaker redisHotPathCircuitBreaker;
    private final HotPathMetrics hotPathMetrics;
    private final UsageStatsRecorder usageStatsRecorder;

    public ApiKeyApplicationService(
            ApiKeyRepository apiKeyRepository,
            ApiKeyRateLimitRepository apiKeyRateLimitRepository,
            ProjectRepository projectRepository,
            ApiKeySecretFactory apiKeySecretFactory,
            RedisHotPathCircuitBreaker redisHotPathCircuitBreaker,
            HotPathMetrics hotPathMetrics,
            UsageStatsRecorder usageStatsRecorder
    ) {
        this.apiKeyRepository = apiKeyRepository;
        this.apiKeyRateLimitRepository = apiKeyRateLimitRepository;
        this.projectRepository = projectRepository;
        this.apiKeySecretFactory = apiKeySecretFactory;
        this.redisHotPathCircuitBreaker = redisHotPathCircuitBreaker;
        this.hotPathMetrics = hotPathMetrics;
        this.usageStatsRecorder = usageStatsRecorder;
    }

    @Override
    @Transactional
    public IssuedApiKeyResult create(CreateApiKeyCommand command) {
        Project project = projectRepository.findById(command.projectId())
                .orElseThrow(() -> new ProjectNotFoundException(command.projectId()));
        if (!command.requesterAdminId().equals(project.adminId())) {
            throw new ProjectAccessDeniedException(command.projectId(), command.requesterAdminId());
        }

        int rateLimitPerSec = command.rateLimitPerSec() == null ? DEFAULT_RATE_LIMIT_PER_SEC : command.rateLimitPerSec();
        int dailyQuota = command.dailyQuota() == null ? DEFAULT_DAILY_QUOTA : command.dailyQuota();
        if (rateLimitPerSec <= 0) {
            throw new IllegalArgumentException("rateLimitPerSec must be positive");
        }
        if (dailyQuota <= 0) {
            throw new IllegalArgumentException("dailyQuota must be positive");
        }

        ApiKeySecretFactory.GeneratedApiKey generated = apiKeySecretFactory.generate();
        ApiKey saved = apiKeyRepository.save(new ApiKey(
                null,
                command.projectId(),
                apiKeySecretFactory.hash(generated.rawKey()),
                generated.keyPrefix(),
                ApiKeyStatus.ACTIVE,
                rateLimitPerSec,
                dailyQuota,
                null,
                null,
                null,
                command.expiresAt()
        ));

        return new IssuedApiKeyResult(
                saved.id(),
                saved.projectId(),
                generated.rawKey(),
                saved.keyPrefix(),
                saved.status(),
                saved.rateLimitPerSec(),
                saved.dailyQuota(),
                saved.createdAt(),
                saved.expiresAt()
        );
    }

    @Override
    public AuthorizedApiKeyResult authorize(String authorizationHeader) {
        String rawKey = extractBearerToken(authorizationHeader);
        String keyHash = apiKeySecretFactory.hash(rawKey);
        Instant now = Instant.now();

        ApiKey apiKey = apiKeyRepository.findByKeyHash(keyHash)
                .orElseThrow(() -> new InvalidApiKeyException("api key is invalid"));

        try {
            validateStatus(apiKey, now);
        } catch (ApiKeyRevokedException | ApiKeyExpiredException exception) {
            usageStatsRecorder.recordBlocked(apiKey.id(), now);
            throw exception;
        }

        RateLimitCheckResult limitCheck = redisHotPathCircuitBreaker.execute(() ->
                apiKeyRateLimitRepository.checkAndConsume(
                        apiKey.id(),
                        apiKey.rateLimitPerSec(),
                        apiKey.dailyQuota(),
                        now
                )
        );

        if (limitCheck.decision() == RateLimitDecision.RATE_LIMIT_EXCEEDED) {
            hotPathMetrics.recordRateLimitBlocked(apiKey.id());
            usageStatsRecorder.recordBlocked(apiKey.id(), now);
            throw new RateLimitExceededException(limitCheck.retryAfterSeconds());
        }
        if (limitCheck.decision() == RateLimitDecision.DAILY_QUOTA_EXCEEDED) {
            hotPathMetrics.recordRateLimitBlocked(apiKey.id());
            usageStatsRecorder.recordBlocked(apiKey.id(), now);
            throw new DailyQuotaExceededException(limitCheck.retryAfterSeconds());
        }

        return new AuthorizedApiKeyResult(apiKey.id(), limitCheck.rateLimitRemaining());
    }

    private String extractBearerToken(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith(BEARER_PREFIX)) {
            throw new InvalidApiKeyException("Authorization header must use Bearer token");
        }

        String rawKey = authorizationHeader.substring(BEARER_PREFIX.length()).trim();
        if (rawKey.isEmpty()) {
            throw new InvalidApiKeyException("Authorization header must use Bearer token");
        }
        return rawKey;
    }

    private void validateStatus(ApiKey apiKey, Instant now) {
        if (apiKey.status() == ApiKeyStatus.REVOKED) {
            throw new ApiKeyRevokedException("api key has been revoked");
        }
        if (apiKey.status() == ApiKeyStatus.EXPIRED) {
            throw new ApiKeyExpiredException("api key has expired");
        }
        if (apiKey.expiresAt() != null && !apiKey.expiresAt().isAfter(now)) {
            throw new ApiKeyExpiredException("api key has expired");
        }
    }
}
