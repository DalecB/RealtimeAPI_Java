package com.jake.realtimeapi.snapshots.api;

import com.jake.realtimeapi.auth.application.usecase.AuthenticateAdminJwtUseCase;
import com.jake.realtimeapi.infra.error.GlobalExceptionHandler;
import com.jake.realtimeapi.snapshots.application.model.SnapshotEntriesResult;
import com.jake.realtimeapi.snapshots.application.model.SnapshotEntryResult;
import com.jake.realtimeapi.snapshots.application.query.GetSnapshotEntriesQuery;
import com.jake.realtimeapi.snapshots.application.usecase.GetSnapshotEntriesUseCase;
import com.jake.realtimeapi.snapshots.domain.exception.SnapshotNotFoundException;
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

@WebMvcTest(controllers = InternalSnapshotEntriesController.class)
@Import(GlobalExceptionHandler.class)
class InternalSnapshotEntriesControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GetSnapshotEntriesUseCase getSnapshotEntriesUseCase;

    @MockitoBean
    private AuthenticateAdminJwtUseCase authenticateAdminJwtUseCase;

    @Test
    void getSnapshotEntries_returnsLatestSnapshotWhenSnapshotAtIsAbsent() throws Exception {
        UUID leaderboardId = UUID.fromString("20000000-0000-0000-0000-000000000001");
        Instant snapshotAt = Instant.parse("2026-03-18T05:00:05Z");

        when(getSnapshotEntriesUseCase.getSnapshotEntries(new GetSnapshotEntriesQuery(leaderboardId, null, 0, 2)))
                .thenReturn(new SnapshotEntriesResult(
                        leaderboardId,
                        54L,
                        snapshotAt,
                        1000,
                        4L,
                        List.of(
                                new SnapshotEntryResult(1, 107L, 2000L),
                                new SnapshotEntryResult(2, 108L, 1500L)
                        )
                ));

        mockMvc.perform(get("/internal/snapshots/{leaderboardId}/entries", leaderboardId)
                        .param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leaderboardId").value(leaderboardId.toString()))
                .andExpect(jsonPath("$.snapshotId").value(54))
                .andExpect(jsonPath("$.snapshotAt").value(snapshotAt.toString()))
                .andExpect(jsonPath("$.topN").value(1000))
                .andExpect(jsonPath("$.total").value(4))
                .andExpect(jsonPath("$.items[0].rank").value(1))
                .andExpect(jsonPath("$.items[0].userId").value("107"))
                .andExpect(jsonPath("$.items[0].score").value(2000));

        verify(getSnapshotEntriesUseCase).getSnapshotEntries(new GetSnapshotEntriesQuery(leaderboardId, null, 0, 2));
    }

    @Test
    void getSnapshotEntries_supportsSpecificSnapshotAt() throws Exception {
        UUID leaderboardId = UUID.fromString("20000000-0000-0000-0000-000000000001");
        Instant snapshotAt = Instant.parse("2026-03-18T05:00:05Z");

        when(getSnapshotEntriesUseCase.getSnapshotEntries(new GetSnapshotEntriesQuery(leaderboardId, snapshotAt, 10, 1)))
                .thenReturn(new SnapshotEntriesResult(
                        leaderboardId,
                        54L,
                        snapshotAt,
                        1000,
                        4L,
                        List.of(new SnapshotEntryResult(11, 999L, 123L))
                ));

        mockMvc.perform(get("/internal/snapshots/{leaderboardId}/entries", leaderboardId)
                        .param("snapshotAt", snapshotAt.toString())
                        .param("offset", "10")
                        .param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].rank").value(11))
                .andExpect(jsonPath("$.items[0].userId").value("999"))
                .andExpect(jsonPath("$.items[0].score").value(123));

        verify(getSnapshotEntriesUseCase).getSnapshotEntries(new GetSnapshotEntriesQuery(leaderboardId, snapshotAt, 10, 1));
    }

    @Test
    void getSnapshotEntries_returns404WhenSnapshotDoesNotExist() throws Exception {
        UUID leaderboardId = UUID.fromString("20000000-0000-0000-0000-000000000001");
        when(getSnapshotEntriesUseCase.getSnapshotEntries(new GetSnapshotEntriesQuery(leaderboardId, null, 0, 50)))
                .thenThrow(new SnapshotNotFoundException("snapshot not found"));

        mockMvc.perform(get("/internal/snapshots/{leaderboardId}/entries", leaderboardId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SNAPSHOT_NOT_FOUND"));
    }

    @Test
    void getSnapshotEntries_returns400WhenLeaderboardIdIsInvalid() throws Exception {
        mockMvc.perform(get("/internal/snapshots/{leaderboardId}/entries", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("leaderboardId must be a valid UUID"));
    }
}
