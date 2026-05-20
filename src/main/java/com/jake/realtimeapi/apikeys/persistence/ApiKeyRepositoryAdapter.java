package com.jake.realtimeapi.apikeys.persistence;

import com.jake.realtimeapi.apikeys.domain.model.ApiKey;
import com.jake.realtimeapi.apikeys.domain.repository.ApiKeyRepository;
import com.jake.realtimeapi.apikeys.persistence.repository.SpringDataApiKeyJpaRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Repository
public class ApiKeyRepositoryAdapter implements ApiKeyRepository {

    private final SpringDataApiKeyJpaRepository jpaRepository;
    private final Duration cacheTtl;
    private final Map<String, CachedApiKey> cacheByKeyHash = new ConcurrentHashMap<>();

    public ApiKeyRepositoryAdapter(
            SpringDataApiKeyJpaRepository jpaRepository,
            @Value("${app.api-key.cache-ttl:1m}") Duration cacheTtl
    ) {
        this.jpaRepository = jpaRepository;
        this.cacheTtl = cacheTtl;
    }

    @Override
    public ApiKey save(ApiKey apiKey) {
        ApiKey saved = ApiKeyPersistenceMapper.toDomain(jpaRepository.save(ApiKeyPersistenceMapper.toEntity(apiKey)));
        cache(saved);
        return saved;
    }

    @Override
    public Optional<ApiKey> findByKeyHash(String keyHash) {
        CachedApiKey cached = cacheByKeyHash.get(keyHash);
        Instant now = Instant.now();
        if (cached != null && !cached.isExpired(now)) {
            return Optional.of(cached.apiKey());
        }

        Optional<ApiKey> found = jpaRepository.findByKeyHash(keyHash).map(ApiKeyPersistenceMapper::toDomain);
        found.ifPresent(this::cache);
        if (found.isEmpty()) {
            cacheByKeyHash.remove(keyHash);
        }
        return found;
    }

    private void cache(ApiKey apiKey) {
        cacheByKeyHash.put(apiKey.keyHash(), new CachedApiKey(apiKey, Instant.now().plus(cacheTtl)));
    }

    private record CachedApiKey(
            ApiKey apiKey,
            Instant expiresAt
    ) {
        private boolean isExpired(Instant now) {
            return !expiresAt.isAfter(now);
        }
    }
}
