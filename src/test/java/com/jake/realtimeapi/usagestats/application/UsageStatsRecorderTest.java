package com.jake.realtimeapi.usagestats.application;

import com.jake.realtimeapi.usagestats.domain.model.UsageBucketType;
import com.jake.realtimeapi.usagestats.domain.model.UsageStatsDelta;
import com.jake.realtimeapi.usagestats.domain.repository.UsageStatsRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class UsageStatsRecorderTest {

    @Mock
    private UsageStatsRepository usageStatsRepository;

    @InjectMocks
    private UsageStatsRecorder usageStatsRecorder;

    @Test
    void recordProcessed_recordsHourAndDayBucketsForReplayMiss() {
        Instant occurredAt = Instant.parse("2026-03-20T01:23:45Z");

        usageStatsRecorder.recordProcessed(7L, false, occurredAt);
        verify(usageStatsRepository, never()).increment(org.mockito.ArgumentMatchers.any());

        usageStatsRecorder.flushPending();

        ArgumentCaptor<UsageStatsDelta> captor = ArgumentCaptor.forClass(UsageStatsDelta.class);
        verify(usageStatsRepository, times(2)).increment(captor.capture());
        Map<UsageBucketType, UsageStatsDelta> deltas = byBucketType(captor.getAllValues());

        assertEquals(Instant.parse("2026-03-20T01:00:00Z"), deltas.get(UsageBucketType.HOUR).bucketStart());
        assertEquals(1L, deltas.get(UsageBucketType.HOUR).requestCount());
        assertEquals(1L, deltas.get(UsageBucketType.HOUR).allowedCount());
        assertEquals(1L, deltas.get(UsageBucketType.HOUR).idempotencyMissCount());

        assertEquals(Instant.parse("2026-03-20T00:00:00Z"), deltas.get(UsageBucketType.DAY).bucketStart());
    }

    @Test
    void recordBlocked_recordsBlockedCountersOnly() {
        Instant occurredAt = Instant.parse("2026-03-20T01:23:45Z");

        usageStatsRecorder.recordBlocked(7L, occurredAt);
        usageStatsRecorder.flushPending();

        ArgumentCaptor<UsageStatsDelta> captor = ArgumentCaptor.forClass(UsageStatsDelta.class);
        verify(usageStatsRepository, times(2)).increment(captor.capture());
        Map<UsageBucketType, UsageStatsDelta> deltas = byBucketType(captor.getAllValues());

        assertEquals(1L, deltas.get(UsageBucketType.HOUR).blockedCount());
        assertEquals(1L, deltas.get(UsageBucketType.DAY).blockedCount());
    }

    @Test
    void flushPending_mergesDeltasForSameBucket() {
        Instant occurredAt = Instant.parse("2026-03-20T01:23:45Z");

        usageStatsRecorder.recordProcessed(7L, false, occurredAt);
        usageStatsRecorder.recordProcessed(7L, true, occurredAt);
        usageStatsRecorder.flushPending();

        ArgumentCaptor<UsageStatsDelta> captor = ArgumentCaptor.forClass(UsageStatsDelta.class);
        verify(usageStatsRepository, times(2)).increment(captor.capture());
        Map<UsageBucketType, UsageStatsDelta> deltas = byBucketType(captor.getAllValues());

        assertEquals(2L, deltas.get(UsageBucketType.HOUR).requestCount());
        assertEquals(2L, deltas.get(UsageBucketType.HOUR).allowedCount());
        assertEquals(1L, deltas.get(UsageBucketType.HOUR).idempotencyHitCount());
        assertEquals(1L, deltas.get(UsageBucketType.HOUR).idempotencyMissCount());
        assertEquals(2L, deltas.get(UsageBucketType.DAY).requestCount());
    }

    private Map<UsageBucketType, UsageStatsDelta> byBucketType(List<UsageStatsDelta> deltas) {
        return deltas.stream()
                .collect(Collectors.toMap(UsageStatsDelta::bucketType, Function.identity()));
    }
}
