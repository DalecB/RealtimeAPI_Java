package com.jake.realtimeapi.leaderboards.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jake.realtimeapi.auth.api.AdminWebMvcConfig;
import com.jake.realtimeapi.auth.application.usecase.AuthenticateAdminJwtUseCase;
import com.jake.realtimeapi.auth.domain.model.AuthenticatedAdminUser;
import com.jake.realtimeapi.infra.error.GlobalExceptionHandler;
import com.jake.realtimeapi.leaderboards.application.command.CreateLeaderboardCommand;
import com.jake.realtimeapi.leaderboards.application.usecase.CreateLeaderboardUseCase;
import com.jake.realtimeapi.leaderboards.application.usecase.GetLeaderboardUseCase;
import com.jake.realtimeapi.leaderboards.application.usecase.ListLeaderboardUseCase;
import com.jake.realtimeapi.leaderboards.domain.model.Leaderboard;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = LeaderboardController.class)
@Import({GlobalExceptionHandler.class, AdminWebMvcConfig.class})
class LeaderboardControllerTest {

    private static final String ADMIN_AUTHORIZATION = "Bearer admin-jwt";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private CreateLeaderboardUseCase createLeaderboardUseCase;

    @MockitoBean
    private GetLeaderboardUseCase getLeaderboardUseCase;

    @MockitoBean
    private ListLeaderboardUseCase listLeaderboardUseCase;

    @MockitoBean
    private AuthenticateAdminJwtUseCase authenticateAdminJwtUseCase;

    @Test
    void create_requiresAuthenticatedAdminAndPassesRequesterId() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID leaderboardId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-03-19T00:00:00Z");

        when(authenticateAdminJwtUseCase.authenticate(ADMIN_AUTHORIZATION))
                .thenReturn(new AuthenticatedAdminUser(1L, "admin-user"));
        when(createLeaderboardUseCase.create(any(CreateLeaderboardCommand.class)))
                .thenReturn(new Leaderboard(leaderboardId, projectId, "board-1", createdAt));

        mockMvc.perform(post("/leaderboards")
                        .header("Authorization", ADMIN_AUTHORIZATION)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "projectId", projectId,
                                "name", "board-1"
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(leaderboardId.toString()))
                .andExpect(jsonPath("$.projectId").value(projectId.toString()))
                .andExpect(jsonPath("$.name").value("board-1"));

        ArgumentCaptor<CreateLeaderboardCommand> captor = ArgumentCaptor.forClass(CreateLeaderboardCommand.class);
        verify(createLeaderboardUseCase).create(captor.capture());
        assertEquals(projectId, captor.getValue().projectId());
        assertEquals("board-1", captor.getValue().name());
        assertEquals(1L, captor.getValue().requesterAdminId());
    }

    @Test
    void create_withoutAuthorization_returns401() throws Exception {
        UUID projectId = UUID.randomUUID();

        mockMvc.perform(post("/leaderboards")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "projectId", projectId,
                                "name", "board-1"
                        ))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_ADMIN_AUTH"));

        verify(createLeaderboardUseCase, never()).create(any());
    }
}
