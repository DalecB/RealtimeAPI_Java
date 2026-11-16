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
        HttpResponse<String> created = post("/users", "{\"externalId\":\"" + externalId + "\"}", "Bearer " + adminToken());
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
        // 이 변형들도 Spring이 같은 핸들러로 라우팅하므로 토큰이 있으면 200이어야 한다.
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
    void adminPosts_requireAdminJwtEvenWithEncodedOrMatrixPath() throws Exception {
        String projectBody = "{\"name\":\"bypass-" + System.nanoTime() + "\"}";
        String leaderboardBody = "{\"projectId\":\"" + UUID.randomUUID() + "\",\"name\":\"bypass\"}";
        String apiKeyBody = "{\"projectId\":\"" + UUID.randomUUID() + "\"}";
        Map<String, String> requests = Map.of(
                "/projects", projectBody,
                "/projects;x=1", projectBody,
                "/%70rojects", projectBody,
                "/leaderboards", leaderboardBody,
                "/leaderboards;x=1", leaderboardBody,
                "/%6Ceaderboards", leaderboardBody,
                "/admin/api-keys", apiKeyBody,
                "/admin;x=1/api-keys", apiKeyBody,
                "/admin/api-keys;x=1", apiKeyBody,
                "/%61dmin/api-keys", apiKeyBody
        );

        for (Map.Entry<String, String> request : requests.entrySet()) {
            String path = request.getKey();
            assertThat(post(path, request.getValue(), null).statusCode()).as(path).isEqualTo(401);
            assertThat(post(path, request.getValue(), "Bearer not-a-jwt").statusCode()).as(path).isEqualTo(401);
        }
    }

    @Test
    void adminPosts_succeedWithAdminJwtOnEveryPathVariant() throws Exception {
        String authorization = "Bearer " + adminToken();

        for (String path : List.of("/projects", "/projects;x=1", "/%70rojects")) {
            String projectId = createProject(path, authorization);

            for (String leaderboardPath : List.of("/leaderboards", "/leaderboards;x=1", "/%6Ceaderboards")) {
                String body = "{\"projectId\":\"" + projectId + "\",\"name\":\"board-" + System.nanoTime() + "\"}";
                assertThat(post(leaderboardPath, body, authorization).statusCode()).as(leaderboardPath).isEqualTo(201);
            }
            for (String apiKeyPath : List.of("/admin/api-keys", "/admin;x=1/api-keys", "/admin/api-keys;x=1", "/%61dmin/api-keys")) {
                String body = "{\"projectId\":\"" + projectId + "\"}";
                assertThat(post(apiKeyPath, body, authorization).statusCode()).as(apiKeyPath).isEqualTo(201);
            }
        }
    }

    @Test
    void users_requireAdminJwtEvenWithEncodedOrMatrixPath() throws Exception {
        String authorization = "Bearer " + adminToken();
        String externalId = "player-" + System.nanoTime();
        String userId = createUser("/users", externalId, authorization);

        for (String path : List.of("/users", "/users;x=1", "/%75sers")) {
            String body = "{\"externalId\":\"player-" + System.nanoTime() + "\"}";
            assertThat(post(path, body, null).statusCode()).as("POST " + path).isEqualTo(401);
            assertThat(post(path, body, "Bearer not-a-jwt").statusCode()).as("POST " + path).isEqualTo(401);
        }
        for (String path : List.of(
                "/users", "/users;x=1", "/%75sers",
                "/users/" + userId, "/users;x=1/" + userId, "/%75sers/" + userId,
                "/users/external/" + ADMIN_USERNAME, "/users/external;x=1/" + ADMIN_USERNAME, "/%75sers/external/" + ADMIN_USERNAME
        )) {
            assertThat(get(path, null).statusCode()).as("GET " + path).isEqualTo(401);
            assertThat(get(path, "Bearer not-a-jwt").statusCode()).as("GET " + path).isEqualTo(401);
        }
    }

    @Test
    void users_succeedWithAdminJwtOnEveryPathVariant() throws Exception {
        String authorization = "Bearer " + adminToken();
        String userId = null;

        for (String path : List.of("/users", "/users;x=1", "/%75sers")) {
            userId = createUser(path, "player-" + System.nanoTime(), authorization);
        }
        for (String path : List.of(
                "/users", "/users;x=1", "/%75sers",
                "/users/" + userId, "/users;x=1/" + userId, "/%75sers/" + userId,
                "/users/external/" + ADMIN_USERNAME, "/%75sers/external/" + ADMIN_USERNAME
        )) {
            assertThat(get(path, authorization).statusCode()).as("GET " + path).isEqualTo(200);
        }
    }

    @Test
    void projectAndLeaderboardReadsStayOpen() throws Exception {
        String authorization = "Bearer " + adminToken();
        String projectId = createProject("/projects", authorization);
        HttpResponse<String> leaderboard = post(
                "/leaderboards",
                "{\"projectId\":\"" + projectId + "\",\"name\":\"open-read\"}",
                authorization
        );
        assertThat(leaderboard.statusCode()).isEqualTo(201);
        String leaderboardId = objectMapper.readTree(leaderboard.body()).path("id").asText();

        for (String path : List.of(
                "/projects",
                "/projects/" + projectId,
                "/leaderboards?projectId=" + projectId,
                "/leaderboards/" + leaderboardId,
                "/leaderboards/" + leaderboardId + "/tops",
                "/leaderboards/" + leaderboardId + "/users/1"
        )) {
            assertThat(get(path, null).statusCode()).as(path).isEqualTo(200);
        }
    }

    @Test
    void actuatorHealthStaysOpen() throws Exception {
        assertThat(get("/actuator/health/liveness", null).statusCode()).isEqualTo(200);
    }

    private String createProject(String path, String authorization) throws Exception {
        HttpResponse<String> response = post(path, "{\"name\":\"auth-" + System.nanoTime() + "\"}", authorization);
        assertThat(response.statusCode()).as(path).isEqualTo(201);
        return objectMapper.readTree(response.body()).path("id").asText();
    }

    private String createUser(String path, String externalId, String authorization) throws Exception {
        HttpResponse<String> response = post(path, "{\"externalId\":\"" + externalId + "\"}", authorization);
        assertThat(response.statusCode()).as(path).isEqualTo(201);
        return objectMapper.readTree(response.body()).path("id").asText();
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
