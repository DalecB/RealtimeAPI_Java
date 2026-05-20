package com.jake.realtimeapi.snapshots.api;

import com.jake.realtimeapi.auth.application.usecase.AuthenticateAdminJwtUseCase;
import com.jake.realtimeapi.infra.error.GlobalExceptionHandler;
import com.jake.realtimeapi.snapshots.application.model.SnapshotStatus;
import com.jake.realtimeapi.snapshots.application.usecase.GetSnapshotStatusUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = InternalSnapshotStatusController.class)
@Import(GlobalExceptionHandler.class)
class InternalSnapshotStatusControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GetSnapshotStatusUseCase getSnapshotStatusUseCase;

    @MockitoBean
    private AuthenticateAdminJwtUseCase authenticateAdminJwtUseCase;

    @Test
    void getStatus_returnsLastSuccessfulSnapshotAtAndLagSeconds() throws Exception {
        Instant lastSuccessfulSnapshotAt = Instant.parse("2026-03-18T00:00:00Z");
        when(getSnapshotStatusUseCase.getStatus()).thenReturn(new SnapshotStatus(lastSuccessfulSnapshotAt, 25L));

        mockMvc.perform(get("/internal/snapshot/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastSuccessfulSnapshotAt").value(lastSuccessfulSnapshotAt.toString()))
                .andExpect(jsonPath("$.snapshotLagSeconds").value(25));

        verify(getSnapshotStatusUseCase).getStatus();
    }

    @Test
    void getStatus_returnsNullTimestampWhenThereHasBeenNoSuccessfulSnapshot() throws Exception {
        when(getSnapshotStatusUseCase.getStatus()).thenReturn(new SnapshotStatus(null, -1L));

        mockMvc.perform(get("/internal/snapshot/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastSuccessfulSnapshotAt").doesNotExist())
                .andExpect(jsonPath("$.snapshotLagSeconds").value(-1));

        verify(getSnapshotStatusUseCase).getStatus();
    }
}
