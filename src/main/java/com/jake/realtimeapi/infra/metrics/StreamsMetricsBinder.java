package com.jake.realtimeapi.infra.metrics;

import com.jake.realtimeapi.events.application.usecase.GetStreamsStatusUseCase;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class StreamsMetricsBinder {

    public StreamsMetricsBinder(GetStreamsStatusUseCase getStreamsStatusUseCase, MeterRegistry meterRegistry) {
        Gauge.builder("stream_pending_entries", getStreamsStatusUseCase, useCase -> {
                    try {
                        return useCase.getStatus().pendingEntries();
                    } catch (RuntimeException exception) {
                        return -1L;
                    }
                })
                .register(meterRegistry);

        Gauge.builder("stream_consumer_lag", getStreamsStatusUseCase, useCase -> {
                    try {
                        return useCase.getStatus().consumerLag();
                    } catch (RuntimeException exception) {
                        return -1L;
                    }
                })
                .register(meterRegistry);
    }
}
