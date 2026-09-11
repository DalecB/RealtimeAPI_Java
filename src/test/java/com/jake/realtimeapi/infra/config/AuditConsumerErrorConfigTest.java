package com.jake.realtimeapi.infra.config;

import com.jake.realtimeapi.events.consumer.AuditConsumerStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;

import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditConsumerErrorConfigTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final AuditConsumerStatus status = new AuditConsumerStatus(meterRegistry, Duration.ofHours(1));
    private final CommonErrorHandler errorHandler = new AuditConsumerErrorConfig()
            .auditConsumerErrorHandler(status, Duration.ofMillis(1), Duration.ofMillis(2));

    @Test
    void transientDatabaseFailure_retriesSameBatch_andRecovers() {
        TopicPartition partition = new TopicPartition(AuditTopicConfig.AUDIT_TOPIC, 0);
        ConsumerRecords<String, String> records = new ConsumerRecords<>(Map.of(
                partition,
                List.of(new ConsumerRecord<>(partition.topic(), partition.partition(), 0, "key", "value"))
        ));
        Consumer<?, ?> consumer = mock(Consumer.class);
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        when(consumer.assignment()).thenReturn(Set.of(partition));
        when(container.isRunning()).thenReturn(true);

        errorHandler.handleBatch(
                new DataAccessResourceFailureException("database unavailable"),
                records,
                consumer,
                container,
                status::recordSuccess
        );

        verify(consumer).pause(Set.of(partition));
        verify(consumer).resume(Set.of(partition));
        verify(container, never()).stopAbnormally(any());
        assertEquals(1, meterRegistry.get("audit_consumer_db_retry_total").counter().count());
        assertEquals(Status.UP, status.health().getStatus());
        assertEquals("RUNNING", status.snapshot().state());
        assertEquals(1, status.snapshot().retryAttempts());
    }

    @Test
    void permanentDatabaseFailure_stopsConsumer_andMarksReadinessDown() {
        MessageListenerContainer container = mock(MessageListenerContainer.class);

        assertThrows(KafkaException.class, () -> errorHandler.handleBatch(
                new BadSqlGrammarException("insert", "bad sql", new SQLException("column missing")),
                ConsumerRecords.empty(),
                mock(Consumer.class),
                container,
                () -> { }
        ));

        await().atMost(Duration.ofSeconds(1)).untilAsserted(() -> verify(container).stopAbnormally(any()));
        assertEquals(1, meterRegistry.get("audit_consumer_db_permanent_failure_total").counter().count());
        assertEquals(Status.DOWN, status.health().getStatus());
        assertEquals("STOPPED", status.snapshot().state());
        assertEquals(false, status.snapshot().ready());
    }
}
