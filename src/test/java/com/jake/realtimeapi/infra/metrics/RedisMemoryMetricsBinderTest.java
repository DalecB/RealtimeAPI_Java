package com.jake.realtimeapi.infra.metrics;

import com.jake.realtimeapi.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

// alerts.yml이 이 이름을 그대로 쓰므로 Micrometer 변환 결과가 아니라 실제 스크랩 출력으로 고정한다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class RedisMemoryMetricsBinderTest {

    private static final long MAXMEMORY_BYTES = 64L * 1024 * 1024;

    @LocalServerPort
    private int port;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    void scrape_exposesRedisUsedMemoryAndMaxmemoryInBytes() throws Exception {
        setMaxmemory(Long.toString(MAXMEMORY_BYTES));
        try {
            String body = scrape();

            assertThat(gaugeValue(body, "redis_used_memory_bytes")).isPositive();
            assertThat(gaugeValue(body, "redis_maxmemory_bytes")).isEqualTo((double) MAXMEMORY_BYTES);
        } finally {
            setMaxmemory("0");
        }
    }

    private void setMaxmemory(String bytes) {
        redisTemplate.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().setConfig("maxmemory", bytes);
            return null;
        });
    }

    private String scrape() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/prometheus")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
    }

    private double gaugeValue(String body, String name) {
        Matcher matcher = Pattern.compile("(?m)^" + name + " (\\S+)$").matcher(body);
        assertThat(matcher.find()).as("%s in scrape", name).isTrue();
        return Double.parseDouble(matcher.group(1));
    }
}
