package com.jake.realtimeapi.events.consumer;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.kafka.listener.RetryListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Component("auditConsumer")
public class AuditConsumerStatus implements RetryListener, HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(AuditConsumerStatus.class);

    private final long unhealthyAfterNanos;
    private final AtomicLong retryStartedAtNanos = new AtomicLong();
    private final AtomicBoolean permanentFailure = new AtomicBoolean();
    private final AtomicBoolean unhealthyLogged = new AtomicBoolean();
    private final Counter retryCounter;
    private final Counter permanentFailureCounter;

    public AuditConsumerStatus(
            MeterRegistry meterRegistry,
            @Value("${events.consumer.db-retry-unhealthy-after:10m}") Duration unhealthyAfter
    ) {
        this.unhealthyAfterNanos = unhealthyAfter.toNanos();
        this.retryCounter = meterRegistry.counter("audit_consumer_db_retry_total");
        this.permanentFailureCounter = meterRegistry.counter("audit_consumer_db_permanent_failure_total");
        Gauge.builder("audit_consumer_db_retry_duration_seconds", this, AuditConsumerStatus::retryDurationSeconds)
                .register(meterRegistry);
    }

    @Override
    public void failedDelivery(ConsumerRecord<?, ?> record, Exception exception, int attempt) {
        // audit consumer는 배치 리스너이므로 사용하지 않는다.
    }

    @Override
    public void failedDelivery(ConsumerRecords<?, ?> records, Exception exception, int attempt) {
        retryStartedAtNanos.compareAndSet(0, System.nanoTime());
        retryCounter.increment();
        if (attempt == 1) {
            log.warn("audit consumer DB retry started records={}", records.count(), exception);
        } else {
            log.warn("audit consumer DB retry attempt={} records={} cause={}",
                    attempt, records.count(), exception.getClass().getSimpleName());
        }
        logUnhealthyOnce();
    }

    public void recordPermanentFailure(Exception exception) {
        permanentFailure.set(true);
        permanentFailureCounter.increment();
        log.error("audit consumer stopped by permanent DB error", exception);
    }

    public void recordSuccess() {
        retryStartedAtNanos.set(0);
        permanentFailure.set(false);
        unhealthyLogged.set(false);
    }

    public Snapshot snapshot() {
        long retryDurationNanos = retryDurationNanos();
        boolean stopped = permanentFailure.get();
        String state = stopped ? "STOPPED" : retryDurationNanos == 0 ? "RUNNING" : "DB_RETRYING";
        return new Snapshot(
                state,
                Math.round(retryDurationNanos / 1_000_000_000.0),
                Math.round(retryCounter.count()),
                !stopped && retryDurationNanos < unhealthyAfterNanos
        );
    }

    @Override
    public Health health() {
        if (permanentFailure.get()) {
            return Health.down().withDetail("state", "permanent-db-error").build();
        }
        if (retryDurationNanos() >= unhealthyAfterNanos) {
            logUnhealthyOnce();
            return Health.down()
                    .withDetail("state", "db-retrying")
                    .withDetail("retryDurationSeconds", Math.round(retryDurationSeconds()))
                    .build();
        }
        return Health.up()
                .withDetail("state", retryStartedAtNanos.get() == 0 ? "running" : "db-retrying")
                .build();
    }

    private void logUnhealthyOnce() {
        if (retryDurationNanos() >= unhealthyAfterNanos && unhealthyLogged.compareAndSet(false, true)) {
            log.error("audit consumer DB retry exceeded unhealthy threshold seconds={}",
                    Duration.ofNanos(unhealthyAfterNanos).toSeconds());
        }
    }

    private double retryDurationSeconds() {
        return retryDurationNanos() / 1_000_000_000.0;
    }

    private long retryDurationNanos() {
        long startedAt = retryStartedAtNanos.get();
        return startedAt == 0 ? 0 : System.nanoTime() - startedAt;
    }

    public record Snapshot(String state, long retryDurationSeconds, long retryAttempts, boolean ready) {
    }
}
