package com.jake.realtimeapi.infra.api;

import com.jake.realtimeapi.auth.application.usecase.AuthenticateAdminJwtUseCase;
import com.jake.realtimeapi.infra.circuitbreaker.CircuitBreakerStatus;
import com.jake.realtimeapi.infra.circuitbreaker.RedisHotPathCircuitBreaker;
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

@WebMvcTest(controllers = InternalCircuitBreakerStatusController.class)
@Import(GlobalExceptionHandler.class)
class InternalCircuitBreakerStatusControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RedisHotPathCircuitBreaker redisHotPathCircuitBreaker;

    @MockitoBean
    private AuthenticateAdminJwtUseCase authenticateAdminJwtUseCase;

    @Test
    void getStatus_returnsCurrentBreakerStateAndFailureRate() throws Exception {
        when(redisHotPathCircuitBreaker.getStatus()).thenReturn(new CircuitBreakerStatus("OPEN", 50.0));

        mockMvc.perform(get("/internal/circuit-breaker/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("OPEN"))
                .andExpect(jsonPath("$.failureRate").value(50.0));

        verify(redisHotPathCircuitBreaker).getStatus();
    }
}
