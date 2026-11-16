package com.jake.realtimeapi.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jake.realtimeapi.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 시드 관리자 + BCrypt 로그인 + /internal/** JWT 보호를 실제 서버(Tomcat)에 붙여 확인한다.
 * 우회 경로 검증은 원본 URI가 그대로 전달돼야 하므로 MockMvc 대신 HttpClient를 쓴다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.admin.bootstrap.username=" + AdminAuthIntegrationTest.ADMIN_USERNAME,
                "app.admin.bootstrap.password=" + AdminAuthIntegrationTest.ADMIN_PASSWORD
        }
)
class AdminAuthIntegrationTest {

    static final String ADMIN_USERNAME = "test-admin";
    static final String ADMIN_PASSWORD = "test-admin-password";

    private static final List<String> INTERNAL_GET_PATHS = List.of(
            "/internal/audit-events/count",
            "/internal/audit-events/leaderboards",
            "/internal/audit-events/recent?leaderboardId=" + UUID.randomUUID(),
            "/internal/snapshot/status",
            "/internal/snapshots/" + UUID.randomUUID() + "/entries",
            "/internal/streams/status",
            "/internal/kafka/audit-topic/status",
            "/internal/circuit-breaker/status"
    );

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void login_succeedsWithSeededAdminAndCorrectPassword() throws Exception {
        HttpResponse<String> response = login(ADMIN_USERNAME, ADMIN_PASSWORD);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(response.body());
        assertThat(body.path("accessToken").asText()).isNotBlank();
        assertThat(body.path("externalId").asText()).isEqualTo(ADMIN_USERNAME);
    }

    @Test
    void login_failsWithSameGenericErrorForWrongMissingPasswordAndUnknownUser() throws Exception {
        HttpResponse<String> wrongPassword = login(ADMIN_USERNAME, "wrong-password");
        HttpResponse<String> missingPassword = post("/auth/login", "{\"externalId\":\"" + ADMIN_USERNAME + "\"}", null);
        HttpResponse<String> unknownUser = login("nobody-" + System.nanoTime(), ADMIN_PASSWORD);

        for (HttpResponse<String> response : List.of(wrongPassword, missingPassword, unknownUser)) {
            assertThat(response.statusCode()).isEqualTo(401);
            JsonNode body = objectMapper.readTree(response.body());
            assertThat(body.path("code").asText()).isEqualTo("INVALID_ADMIN_AUTH");
            assertThat(body.path("message").asText()).isEqualTo("admin login is invalid");
        }
    }

    @Test
    void login_rejectsPlayerCreatedThroughUsersApi() throws Exception {
        String externalId = "player-" + System.nanoTime();
        HttpResponse<String> created = post("/users", "{\"externalId\":\"" + externalId + "\"}", null);
        assertThat(created.statusCode()).isEqualTo(201);

        assertThat(login(externalId, null).statusCode()).isEqualTo(401);
        assertThat(login(externalId, "").statusCode()).isEqualTo(401);
        assertThat(login(externalId, ADMIN_PASSWORD).statusCode()).isEqualTo(401);
    }

    @Test
    void internal_requiresAdminJwt() throws Exception {
        for (String path : INTERNAL_GET_PATHS) {
            assertThat(get(path, null).statusCode()).as(path).isEqualTo(401);
            assertThat(get(path, "Bearer not-a-jwt").statusCode()).as(path).isEqualTo(401);
        }
    }

    @Test
    void internal_returns200WithAdminJwt() throws Exception {
        String authorization = "Bearer " + adminToken();

        assertThat(get("/internal/circuit-breaker/status", authorization).statusCode()).isEqualTo(200);
        assertThat(get("/internal/audit-events/count", authorization).statusCode()).isEqualTo(200);
        assertThat(get("/internal/streams/status", authorization).statusCode()).isEqualTo(200);
    }

    @Test
    void internal_cannotBeBypassedWithEncodedOrMatrixPath() throws Exception {
        // 이 경로들은 Spring이 /internal/audit-events/count 핸들러로 라우팅한다(토큰이 있으면 200).
        // 원본 URI 문자열 비교였다면 인증을 건너뛰었을 변형이다.
        String authorization = "Bearer " + adminToken();
        for (String path : List.of(
                "/%69nternal/audit-events/count",
                "/internal;x=1/audit-events/count",
                "/internal/audit-events;x=1/count"
        )) {
            assertThat(get(path, null).statusCode()).as(path).isEqualTo(401);
            assertThat(get(path, authorization).statusCode()).as(path).isEqualTo(200);
        }
    }

    @Test
    void actuatorHealthStaysOpen() throws Exception {
        assertThat(get("/actuator/health/liveness", null).statusCode()).isEqualTo(200);
    }

    private String adminToken() throws Exception {
        HttpResponse<String> response = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        assertThat(response.statusCode()).isEqualTo(200);
        return objectMapper.readTree(response.body()).path("accessToken").asText();
    }

    private HttpResponse<String> login(String externalId, String password) throws Exception {
        String body = password == null
                ? objectMapper.writeValueAsString(Map.of("externalId", externalId))
                : objectMapper.writeValueAsString(Map.of("externalId", externalId, "password", password));
        return post("/auth/login", body, null);
    }

    private HttpResponse<String> post(String path, String json, String authorization) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (authorization != null) {
            builder.header("Authorization", authorization);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String authorization) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path)).GET();
        if (authorization != null) {
            builder.header("Authorization", authorization);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
