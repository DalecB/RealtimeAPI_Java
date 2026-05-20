package com.jake.realtimeapi.apikeys.persistence;

import com.jake.realtimeapi.apikeys.domain.model.ApiKey;
import com.jake.realtimeapi.apikeys.domain.model.ApiKeyStatus;
import com.jake.realtimeapi.apikeys.persistence.entity.ApiKeyJpaEntity;
import com.jake.realtimeapi.apikeys.persistence.repository.SpringDataApiKeyJpaRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiKeyRepositoryAdapterTest {

    @Mock
    private SpringDataApiKeyJpaRepository jpaRepository;

    @Test
    void findByKeyHash_usesLocalCacheWithinTtl() {
        UUID projectId = UUID.randomUUID();
        Instant now = Instant.parse("2026-03-30T00:00:00Z");
        ApiKeyJpaEntity entity = new ApiKeyJpaEntity(
                1L,
                projectId,
                "hashed",
                "rk_prefix",
                ApiKeyStatus.ACTIVE,
                100,
                1000,
                now,
                now,
                null,
                null
        );
        when(jpaRepository.findByKeyHash("hashed")).thenReturn(Optional.of(entity));

        ApiKeyRepositoryAdapter adapter = new ApiKeyRepositoryAdapter(jpaRepository, Duration.ofMinutes(1));

        Optional<ApiKey> first = adapter.findByKeyHash("hashed");
        Optional<ApiKey> second = adapter.findByKeyHash("hashed");

        assertTrue(first.isPresent());
        assertTrue(second.isPresent());
        assertEquals(1L, first.get().id());
        verify(jpaRepository, times(1)).findByKeyHash("hashed");
    }
}
