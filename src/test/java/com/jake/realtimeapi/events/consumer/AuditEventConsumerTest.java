package com.jake.realtimeapi.events.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jake.realtimeapi.infra.config.AuditTopicConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class AuditEventConsumerTest {

    private final AuditEventRepository repository = mock(AuditEventRepository.class);
    private final ObjectMapper objectMapper = org.mockito.Mockito.spy(new ObjectMapper());
    private final DeadLetterPublishingRecoverer recoverer = mock(DeadLetterPublishingRecoverer.class);
    private final AuditConsumerStatus status = mock(AuditConsumerStatus.class);
    private final AuditEventConsumer consumer = new AuditEventConsumer(repository, objectMapper, recoverer, status, 0);

    @Test
    void invalidRecord_isRetriedThreeTimes_andOnlyItIsSentToDlt() throws Exception {
        String key = UUID.randomUUID().toString();
        String validJson = """
                {"eventId":"1-0","type":"new","userId":"1","delta":"2","apiKeyId":"3",
                 "idempotencyKey":"%s"}
                """.formatted(UUID.randomUUID());
        ConsumerRecord<String, String> valid = record(0, key, validJson);
        ConsumerRecord<String, String> invalid = record(1, key, "not-json");

        consumer.consume(List.of(valid, invalid));

        verify(objectMapper, times(3)).readTree("not-json");
        var rows = forClass(List.class);
        verify(repository).insertIgnoringDuplicates(rows.capture());
        assertEquals(1, rows.getValue().size());
        verify(recoverer).accept(eq(invalid), any(RuntimeException.class));
        verify(status).recordSuccess();
    }

    @Test
    void dltFailure_isPropagated() {
        ConsumerRecord<String, String> invalid = record(0, UUID.randomUUID().toString(), "not-json");
        doThrow(new IllegalStateException("DLT unavailable"))
                .when(recoverer).accept(any(), any(RuntimeException.class));

        assertThrows(IllegalStateException.class, () -> consumer.consume(List.of(invalid)));
    }

    private ConsumerRecord<String, String> record(long offset, String key, String value) {
        return new ConsumerRecord<>(AuditTopicConfig.AUDIT_TOPIC, 0, offset, key, value);
    }
}
