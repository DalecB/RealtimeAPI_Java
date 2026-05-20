package com.jake.realtimeapi.apikeys.persistence.repository;

import com.jake.realtimeapi.apikeys.persistence.entity.ApiKeyJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SpringDataApiKeyJpaRepository extends JpaRepository<ApiKeyJpaEntity, Long> {

    Optional<ApiKeyJpaEntity> findByKeyHash(String keyHash);
}
