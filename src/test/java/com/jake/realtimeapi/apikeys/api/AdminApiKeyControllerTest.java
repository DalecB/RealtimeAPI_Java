package com.jake.realtimeapi.apikeys.api;

import com.jake.realtimeapi.auth.api.AdminWebMvcConfig;
import com.jake.realtimeapi.auth.application.usecase.AuthenticateAdminJwtUseCase;
import com.jake.realtimeapi.auth.domain.model.AuthenticatedAdminUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jake.realtimeapi.apikeys.application.command.CreateApiKeyCommand;
import com.jake.realtimeapi.apikeys.application.model.IssuedApiKeyResult;
import com.jake.realtimeapi.apikeys.application.usecase.CreateApiKeyUseCase;
import com.jake.realtimeapi.apikeys.domain.model.ApiKeyStatus;
import com.jake.realtimeapi.infra.error.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AdminApiKeyController.class)
@Import({GlobalExceptionHandler.class, AdminWebMvcConfig.class})
class AdminApiKeyControllerTest {

    private static final String ADMIN_AUTHORIZATION = "Bearer admin-jwt";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private CreateApiKeyUseCase createApiKeyUseCase;

    @MockitoBean
    private AuthenticateAdminJwtUseCase authenticateAdminJwtUseCase;

    @Test
    void create_returnsIssuedApiKey() throws Exception {
        UUID projectId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-03-19T00:00:00Z");
        Instant expiresAt = Instant.parse("2026-03-20T00:00:00Z");

        when(authenticateAdminJwtUseCase.authenticate(ADMIN_AUTHORIZATION))
                .thenReturn(new AuthenticatedAdminUser(1L, "admin-user"));
        when(createApiKeyUseCase.create(new CreateApiKeyCommand(projectId, 1L, 200, 5000, expiresAt)))
                .thenReturn(new IssuedApiKeyResult(
                        1L,
                        projectId,
                        "rk_test_secret",
                        "rk_test_secret",
                        ApiKeyStatus.ACTIVE,
                        200,
                        5000,
                        createdAt,
                        expiresAt
                ));

        mockMvc.perform(post("/admin/api-keys")
                        .header("Authorization", ADMIN_AUTHORIZATION)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "projectId", projectId,
                                "rateLimitPerSec", 200,
                                "dailyQuota", 5000,
                                "expiresAt", expiresAt.toString()
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.projectId").value(projectId.toString()))
                .andExpect(jsonPath("$.rawKey").value("rk_test_secret"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.rateLimitPerSec").value(200))
                .andExpect(jsonPath("$.dailyQuota").value(5000))
                .andExpect(jsonPath("$.createdAt").value(createdAt.toString()))
                .andExpect(jsonPath("$.expiresAt").value(expiresAt.toString()));

        verify(createApiKeyUseCase).create(new CreateApiKeyCommand(projectId, 1L, 200, 5000, expiresAt));
    }
}
