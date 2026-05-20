package com.jake.realtimeapi.leaderboards.api;

import com.jake.realtimeapi.auth.application.usecase.AuthenticateAdminJwtUseCase;
import com.jake.realtimeapi.events.application.usecase.GetTopRanksUseCase;
import com.jake.realtimeapi.events.application.usecase.GetUserRankUseCase;
import com.jake.realtimeapi.events.domain.model.TopRankItem;
import com.jake.realtimeapi.events.domain.model.TopRanksResult;
import com.jake.realtimeapi.events.domain.model.UserRankResult;
import com.jake.realtimeapi.infra.error.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = LeaderboardRankingController.class)
@Import(GlobalExceptionHandler.class)
class LeaderboardRankingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GetTopRanksUseCase getTopRanksUseCase;

    @MockitoBean
    private GetUserRankUseCase getUserRankUseCase;

    @MockitoBean
    private AuthenticateAdminJwtUseCase authenticateAdminJwtUseCase;

    @Test
    void getUserRank_parsesStringUserIdAndReturnsStringResponse() throws Exception {
        UUID leaderboardId = UUID.randomUUID();

        when(getUserRankUseCase.getUserRank(any()))
                .thenReturn(new UserRankResult(1L, 1000L, 1));

        mockMvc.perform(get("/leaderboards/{leaderboardId}/users/{userId}", leaderboardId, "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("1"))
                .andExpect(jsonPath("$.score").value(1000))
                .andExpect(jsonPath("$.rank").value(1));

        verify(getUserRankUseCase).getUserRank(any());
    }

    @Test
    void getUserRank_returns400WhenUserIdIsNotNumeric() throws Exception {
        UUID leaderboardId = UUID.randomUUID();

        mockMvc.perform(get("/leaderboards/{leaderboardId}/users/{userId}", leaderboardId, "user-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void getTopRanks_mapsInternalLongUserIdsToStringResponse() throws Exception {
        UUID leaderboardId = UUID.randomUUID();

        when(getTopRanksUseCase.getTopRanks(any()))
                .thenReturn(new TopRanksResult(
                        leaderboardId,
                        List.of(
                                new TopRankItem(1, 1L, 1000),
                                new TopRankItem(2, 2L, 800)
                        ),
                        2L
                ));

        mockMvc.perform(get("/leaderboards/{leaderboardId}/tops", leaderboardId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].userId").value("1"))
                .andExpect(jsonPath("$.items[1].userId").value("2"))
                .andExpect(jsonPath("$.total").value(2));
    }

    @Test
    void getTopRanks_returns400WhenOffsetExceedsPrdLimit() throws Exception {
        UUID leaderboardId = UUID.randomUUID();

        mockMvc.perform(get("/leaderboards/{leaderboardId}/tops", leaderboardId)
                        .param("offset", "10000")
                        .param("limit", "50"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"))
                .andExpect(jsonPath("$.message").value("offset must be between 0 and 9999, but was 10000"));

        verify(getTopRanksUseCase, never()).getTopRanks(any());
    }

    @Test
    void getTopRanks_returns400WhenLimitExceedsPrdLimit() throws Exception {
        UUID leaderboardId = UUID.randomUUID();

        mockMvc.perform(get("/leaderboards/{leaderboardId}/tops", leaderboardId)
                        .param("offset", "0")
                        .param("limit", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"))
                .andExpect(jsonPath("$.message").value("limit must be between 1 and 100, but was 101"));

        verify(getTopRanksUseCase, never()).getTopRanks(any());
    }
}
