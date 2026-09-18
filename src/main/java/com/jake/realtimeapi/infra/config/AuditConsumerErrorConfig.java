package com.jake.realtimeapi.infra.config;

import com.jake.realtimeapi.events.consumer.AuditConsumerStatus;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler;
import org.springframework.kafka.listener.CommonDelegatingErrorHandler;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.BackOffExecution;
import org.springframework.util.backoff.ExponentialBackOff;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

@Configuration
public class AuditConsumerErrorConfig {

    @Bean
    public CommonErrorHandler auditConsumerErrorHandler(
            AuditConsumerStatus status,
            @Value("${events.consumer.db-retry-initial-delay:10s}") Duration initialDelay,
            @Value("${events.consumer.db-retry-max-delay:5m}") Duration maxDelay
    ) {
        DefaultErrorHandler retryTransientDatabaseFailure = new DefaultErrorHandler(
                databaseRetryBackOff(initialDelay, maxDelay)
        );
        retryTransientDatabaseFailure.setRetryListeners(status);

        CommonContainerStoppingErrorHandler stopOnPermanentDatabaseFailure =
                new CommonContainerStoppingErrorHandler() {
                    @Override
                    public void handleBatch(
                            Exception exception,
                            ConsumerRecords<?, ?> records,
                            Consumer<?, ?> consumer,
                            MessageListenerContainer container,
                            Runnable invokeListener
                    ) {
                        status.recordPermanentFailure(exception);
                        super.handleBatch(exception, records, consumer, container, invokeListener);
                    }
                };

        CommonDelegatingErrorHandler errorHandler =
                new CommonDelegatingErrorHandler(new CommonContainerStoppingErrorHandler());
        Map<Class<? extends Throwable>, CommonErrorHandler> delegates = new LinkedHashMap<>();
        delegates.put(DataAccessResourceFailureException.class, retryTransientDatabaseFailure);
        delegates.put(TransientDataAccessException.class, retryTransientDatabaseFailure);
        delegates.put(DataAccessException.class, stopOnPermanentDatabaseFailure);
        errorHandler.setErrorHandlers(delegates);
        errorHandler.setCauseChainTraversing(true);
        return errorHandler;
    }

    static BackOff databaseRetryBackOff(Duration initialDelay, Duration maxDelay) {
        ExponentialBackOff exponential = new ExponentialBackOff(initialDelay.toMillis(), 2);
        exponential.setMaxInterval(maxDelay.toMillis());
        return () -> {
            BackOffExecution execution = exponential.start();
            return () -> {
                long interval = execution.nextBackOff();
                if (interval == BackOffExecution.STOP) {
                    return interval;
                }
                return ThreadLocalRandom.current().nextLong((interval + 1) / 2, interval + 1);
            };
        };
    }
}
