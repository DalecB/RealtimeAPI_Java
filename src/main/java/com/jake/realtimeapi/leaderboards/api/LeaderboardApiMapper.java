package com.jake.realtimeapi.leaderboards.api;

import com.jake.realtimeapi.leaderboards.api.dto.LeaderboardResponse;
import com.jake.realtimeapi.leaderboards.api.dto.ListLeaderboardResponse;
import com.jake.realtimeapi.leaderboards.domain.model.Leaderboard;

import java.util.List;

public class LeaderboardApiMapper {

    private LeaderboardApiMapper() {}

    public static LeaderboardResponse toResponse(Leaderboard leaderboard) {
        return new LeaderboardResponse(leaderboard.id(), leaderboard.projectId(), leaderboard.name(), leaderboard.createdAt());
    }

    public static ListLeaderboardResponse toResponse(List<Leaderboard> list) {
        List<LeaderboardResponse> items = list.stream()
                .map(LeaderboardApiMapper::toResponse)
                .toList();
        return new ListLeaderboardResponse(items, list.size());
    }
}
