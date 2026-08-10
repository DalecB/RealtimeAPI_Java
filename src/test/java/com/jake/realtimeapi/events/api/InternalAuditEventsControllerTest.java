package com.jake.realtimeapi.events.api;

import com.jake.realtimeapi.auth.application.usecase.AuthenticateAdminJwtUseCase;
import com.jake.realtimeapi.events.consumer.AuditEventQueryRepository;
import com.jake.realtimeapi.infra.error.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = InternalAuditEventsController.class)
@Import(GlobalExceptionHandler.class)
class InternalAuditEventsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuditEventQueryRepository auditEventQueryRepository;

    @MockitoBean
    private AuthenticateAdminJwtUseCase authenticateAdminJwtUseCase;

    @Test
    void recent_returnsLatestAuditEventsWithIdsAsStrings() throws Exception {
        UUID leaderboardId = UUID.fromString("20000000-0000-0000-0000-000000000001");
        Instant eventTime = Instant.parse("2026-08-10T01:02:03Z");
        when(auditEventQueryRepository.recent(leaderboardId, 20)).thenReturn(List.of(
                new AuditEventQueryRepository.RecentAuditEvent(
                        leaderboardId,
                        eventTime,
                        "1786323723000-0",
                        "conflict",
                        7L,
                        26L,
                        9L,
                        "10000000-0000-0000-0000-000000000001"
                )
        ));

        mockMvc.perform(get("/internal/audit-events/recent")
                        .param("leaderboardId", leaderboardId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].eventTime").value(eventTime.toString()))
                .andExpect(jsonPath("$[0].eventId").value("1786323723000-0"))
                .andExpect(jsonPath("$[0].eventType").value("conflict"))
                .andExpect(jsonPath("$[0].userId").value("7"))
                .andExpect(jsonPath("$[0].delta").value(26))
                .andExpect(jsonPath("$[0].apiKeyId").value("9"))
                .andExpect(jsonPath("$[0].idempotencyKey")
                        .value("10000000-0000-0000-0000-000000000001"));

        verify(auditEventQueryRepository).recent(leaderboardId, 20);
    }

    @Test
    void recent_rejectsLimitOutsideInspectorBoundary() throws Exception {
        mockMvc.perform(get("/internal/audit-events/recent")
                        .param("leaderboardId", UUID.randomUUID().toString())
                        .param("limit", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"))
                .andExpect(jsonPath("$.message").value("limit must be between 1 and 100"));
    }
}
