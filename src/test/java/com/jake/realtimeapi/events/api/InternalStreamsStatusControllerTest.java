package com.jake.realtimeapi.events.api;

import com.jake.realtimeapi.auth.application.usecase.AuthenticateAdminJwtUseCase;
import com.jake.realtimeapi.events.application.usecase.GetStreamsStatusUseCase;
import com.jake.realtimeapi.events.domain.model.StreamsStatus;
import com.jake.realtimeapi.infra.error.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = InternalStreamsStatusController.class)
@Import(GlobalExceptionHandler.class)
class InternalStreamsStatusControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GetStreamsStatusUseCase getStreamsStatusUseCase;

    @MockitoBean
    private AuthenticateAdminJwtUseCase authenticateAdminJwtUseCase;

    @Test
    void getStatus_returnsPendingLagAndLastDeliveredId() throws Exception {
        when(getStreamsStatusUseCase.getStatus()).thenReturn(new StreamsStatus(0L, 12L, "1710000000000-0"));

        mockMvc.perform(get("/internal/streams/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingEntries").value(0))
                .andExpect(jsonPath("$.consumerLag").value(12))
                .andExpect(jsonPath("$.lastDeliveredId").value("1710000000000-0"));

        verify(getStreamsStatusUseCase).getStatus();
    }
}
