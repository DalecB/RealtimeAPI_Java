package com.jake.realtimeapi.events.persistence;

import com.jake.realtimeapi.events.domain.exception.IdempotencyKeyReuseMismatchException;
import com.jake.realtimeapi.events.domain.model.EventPayload;
import com.jake.realtimeapi.events.domain.model.ProcessEventResult;
import com.jake.realtimeapi.events.persistence.redis.ProcessEventLuaExecutor;
import com.jake.realtimeapi.infra.circuitbreaker.RedisCircuitBreakerOpenException;
import com.jake.realtimeapi.infra.circuitbreaker.RedisHotPathCircuitBreaker;
import com.jake.realtimeapi.infra.metrics.HotPathMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EventCommandRepositoryAdapterTest {

    @Mock
    private ProcessEventLuaExecutor processEventLuaExecutor;

    @Mock
    private RedisHotPathCircuitBreaker redisHotPathCircuitBreaker;

    @Mock
    private HotPathMetrics hotPathMetrics;

    private EventCommandRepositoryAdapter repository;

    @BeforeEach
    void setUp() {
        repository = new EventCommandRepositoryAdapter(processEventLuaExecutor, redisHotPathCircuitBreaker, hotPathMetrics);
    }

    @Test
    void process_returnsResultWhenRedisWriteSucceeds() {
        EventPayload payload = new EventPayload(UUID.randomUUID(), 101L, 50L, UUID.randomUUID());
        doAnswer(invocation -> {
            Supplier<?> supplier = invocation.getArgument(0);
            return supplier.get();
        }).when(redisHotPathCircuitBreaker).execute(any());

        when(processEventLuaExecutor.execute(
                eq(payload.leaderboardId()),
                eq(payload.idempotencyKey()),
                eq("101"),
                eq(50L),
                eq(86400L),
                anyString()
        )).thenReturn(new ProcessEventLuaExecutor.LuaExecutionResult(true, "f34ad01d9184e83d23855dd4aa9011cfdbcbf8199b13f40f81f26871032d9a03"));

        ProcessEventResult result = repository.process(payload);

        assertEquals(payload.idempotencyKey(), result.idempotencyKey());
        assertFalse(result.replayed());
        verify(processEventLuaExecutor).execute(
                eq(payload.leaderboardId()),
                eq(payload.idempotencyKey()),
                eq("101"),
                eq(50L),
                eq(86400L),
                anyString()
        );
    }

    @Test
    void process_returnsOriginalProcessedAtWhenRequestIsReplayed() {
        EventPayload payload = new EventPayload(UUID.randomUUID(), 101L, 50L, UUID.randomUUID());
        Instant firstProcessedAt = Instant.parse("2026-03-30T10:15:30Z");
        doAnswer(invocation -> {
            Supplier<?> supplier = invocation.getArgument(0);
            return supplier.get();
        }).when(redisHotPathCircuitBreaker).execute(any());

        when(processEventLuaExecutor.execute(
                eq(payload.leaderboardId()),
                eq(payload.idempotencyKey()),
                eq("101"),
                eq(50L),
                eq(86400L),
                anyString()
        )).thenReturn(new ProcessEventLuaExecutor.LuaExecutionResult(
                false,
                "v2:358712c8a759753081014787e39822f9afed47aee1ce8a3026ce855f3afcf4e6:" + firstProcessedAt.toEpochMilli()
        ));

        ProcessEventResult result = repository.process(payload);

        assertTrue(result.replayed());
        assertEquals(firstProcessedAt, result.processedAt());
    }

    @Test
    void process_acceptsLegacyHashOnlyReplayDuringMigrationWindow() {
        EventPayload payload = new EventPayload(UUID.randomUUID(), 101L, 50L, UUID.randomUUID());
        Instant before = Instant.now();
        doAnswer(invocation -> {
            Supplier<?> supplier = invocation.getArgument(0);
            return supplier.get();
        }).when(redisHotPathCircuitBreaker).execute(any());

        when(processEventLuaExecutor.execute(
                eq(payload.leaderboardId()),
                eq(payload.idempotencyKey()),
                eq("101"),
                eq(50L),
                eq(86400L),
                anyString()
        )).thenReturn(new ProcessEventLuaExecutor.LuaExecutionResult(
                false,
                "358712c8a759753081014787e39822f9afed47aee1ce8a3026ce855f3afcf4e6"
        ));

        ProcessEventResult result = repository.process(payload);
        Instant after = Instant.now();

        assertTrue(result.replayed());
        assertTrue(!result.processedAt().isBefore(before) && !result.processedAt().isAfter(after));
    }

    @Test
    void process_rethrowsWhenCircuitBreakerIsOpen() {
        EventPayload payload = new EventPayload(UUID.randomUUID(), 101L, 50L, UUID.randomUUID());
        doThrow(new RedisCircuitBreakerOpenException(10))
                .when(redisHotPathCircuitBreaker).execute(any());

        RedisCircuitBreakerOpenException exception = assertThrows(
                RedisCircuitBreakerOpenException.class,
                () -> repository.process(payload)
        );

        assertEquals(10L, exception.getRetryAfterSeconds());
    }

    @Test
    void process_throwsConflictWhenIdempotencyPayloadHashDiffers() {
        EventPayload payload = new EventPayload(UUID.randomUUID(), 101L, 50L, UUID.randomUUID());
        doAnswer(invocation -> {
            Supplier<?> supplier = invocation.getArgument(0);
            return supplier.get();
        }).when(redisHotPathCircuitBreaker).execute(any());

        when(processEventLuaExecutor.execute(
                eq(payload.leaderboardId()),
                eq(payload.idempotencyKey()),
                eq("101"),
                eq(50L),
                eq(86400L),
                anyString()
        )).thenReturn(new ProcessEventLuaExecutor.LuaExecutionResult(false, "another-hash"));

        assertThrows(IdempotencyKeyReuseMismatchException.class, () -> repository.process(payload));
    }
}
