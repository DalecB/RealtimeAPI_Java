package com.jake.realtimeapi.leaderboards.api;

import com.jake.realtimeapi.events.application.query.GetTopRanksQuery;
import com.jake.realtimeapi.events.application.query.GetUserRankQuery;
import com.jake.realtimeapi.events.application.usecase.GetTopRanksUseCase;
import com.jake.realtimeapi.events.application.usecase.GetUserRankUseCase;
import com.jake.realtimeapi.leaderboards.api.dto.TopRanksResponse;
import com.jake.realtimeapi.leaderboards.api.dto.TopRanksResponse.TopRankItemResponse;
import com.jake.realtimeapi.leaderboards.api.dto.UserRankResponse;
import com.jake.realtimeapi.support.userid.UserIdCodec;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/leaderboards")
public class LeaderboardRankingController {

    private final GetTopRanksUseCase getTopRanksUseCase;
    private final GetUserRankUseCase getUserRankUseCase;

    public LeaderboardRankingController(
            GetTopRanksUseCase getTopRanksUseCase,
            GetUserRankUseCase getUserRankUseCase
    ) {
        this.getTopRanksUseCase = getTopRanksUseCase;
        this.getUserRankUseCase = getUserRankUseCase;
    }

    @GetMapping("/{leaderboardId}/tops")
    public TopRanksResponse getTopRanks(
            @PathVariable UUID leaderboardId,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit
    ) {

        var result = getTopRanksUseCase.getTopRanks(
                new GetTopRanksQuery(
                        leaderboardId,
                        offset,
                        limit
                )
        );

        List<TopRankItemResponse> items = result.items().stream()
                .map(item -> new TopRankItemResponse(item.rank(), UserIdCodec.format(item.userId()), item.score()))
                .toList();

        return new TopRanksResponse(result.leaderboardId(), items, result.total());
    }

    @GetMapping("/{leaderboardId}/users/{userId}")
    public UserRankResponse getUserRank(
            @PathVariable UUID leaderboardId,
            @PathVariable String userId
    ) {

        var result = getUserRankUseCase.getUserRank(
                new GetUserRankQuery(
                        leaderboardId,
                        UserIdCodec.parse(userId)
                )
        );

        return new UserRankResponse(UserIdCodec.format(result.userId()), result.score(), result.rank());
    }
}
