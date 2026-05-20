package com.jake.realtimeapi.apikeys.persistence;

import com.jake.realtimeapi.apikeys.domain.model.ApiKey;
import com.jake.realtimeapi.apikeys.persistence.entity.ApiKeyJpaEntity;

public final class ApiKeyPersistenceMapper {

    private ApiKeyPersistenceMapper() {
    }

    public static ApiKeyJpaEntity toEntity(ApiKey apiKey) {
        return new ApiKeyJpaEntity(
                apiKey.id(),
                apiKey.projectId(),
                apiKey.keyHash(),
                apiKey.keyPrefix(),
                apiKey.status(),
                apiKey.rateLimitPerSec(),
                apiKey.dailyQuota(),
                apiKey.createdAt(),
                apiKey.updatedAt(),
                apiKey.revokedAt(),
                apiKey.expiresAt()
        );
    }

    public static ApiKey toDomain(ApiKeyJpaEntity entity) {
        return new ApiKey(
                entity.getId(),
                entity.getProjectId(),
                entity.getKeyHash(),
                entity.getKeyPrefix(),
                entity.getStatus(),
                entity.getRateLimitPerSec(),
                entity.getDailyQuota(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getRevokedAt(),
                entity.getExpiresAt()
        );
    }
}
