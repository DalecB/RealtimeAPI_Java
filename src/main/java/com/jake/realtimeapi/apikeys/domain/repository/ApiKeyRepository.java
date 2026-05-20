package com.jake.realtimeapi.apikeys.domain.repository;

import com.jake.realtimeapi.apikeys.domain.model.ApiKey;

import java.util.Optional;

public interface ApiKeyRepository {

    ApiKey save(ApiKey apiKey);

    Optional<ApiKey> findByKeyHash(String keyHash);
}
