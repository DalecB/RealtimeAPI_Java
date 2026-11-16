package com.jake.realtimeapi.infra.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.BaseUnits;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Properties;

@Component
public class RedisMemoryMetricsBinder {

    public RedisMemoryMetricsBinder(StringRedisTemplate redisTemplate, MeterRegistry meterRegistry) {
        register("redis_used_memory", "used_memory", redisTemplate, meterRegistry);
        register("redis_maxmemory", "maxmemory", redisTemplate, meterRegistry);
    }

    private static void register(String name, String infoField, StringRedisTemplate redisTemplate, MeterRegistry meterRegistry) {
        Gauge.builder(name, redisTemplate, template -> {
                    try {
                        Properties info = template.execute((RedisCallback<Properties>) connection ->
                                connection.serverCommands().info("memory"));
                        return Double.parseDouble(info.getProperty(infoField));
                    } catch (RuntimeException exception) {
                        return -1L;
                    }
                })
                .baseUnit(BaseUnits.BYTES)
                .register(meterRegistry);
    }
}
