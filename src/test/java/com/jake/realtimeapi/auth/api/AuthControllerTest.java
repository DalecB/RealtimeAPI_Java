package com.jake.realtimeapi.auth.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jake.realtimeapi.auth.api.dto.AdminLoginRequest;
import com.jake.realtimeapi.auth.application.model.AdminLoginResult;
import com.jake.realtimeapi.auth.application.usecase.AuthenticateAdminJwtUseCase;
import com.jake.realtimeapi.auth.application.usecase.AdminLoginUseCase;
import com.jake.realtimeapi.infra.error.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AuthController.class)
@Import(GlobalExceptionHandler.class)
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AdminLoginUseCase adminLoginUseCase;

    @MockitoBean
    private AuthenticateAdminJwtUseCase authenticateAdminJwtUseCase;

    @Test
    void login_returnsJwtToken() throws Exception {
        Instant expiresAt = Instant.parse("2026-03-19T02:00:00Z");
        when(adminLoginUseCase.login(any()))
                .thenReturn(new AdminLoginResult("jwt-token", "Bearer", expiresAt, 1L, "admin-user"));

        mockMvc.perform(post("/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AdminLoginRequest("admin-user"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("jwt-token"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.userId").value(1L))
                .andExpect(jsonPath("$.externalId").value("admin-user"))
                .andExpect(jsonPath("$.expiresAt").value(expiresAt.toString()));

        verify(adminLoginUseCase).login(any());
    }
}
