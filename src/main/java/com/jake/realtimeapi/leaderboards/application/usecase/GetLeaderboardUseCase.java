package com.jake.realtimeapi.leaderboards.application.usecase;

import com.jake.realtimeapi.leaderboards.domain.model.Leaderboard;

import java.util.UUID;

public interface GetLeaderboardUseCase {

    Leaderboard getById(UUID id);

    Leaderboard getByName(UUID projectId, String name);
}
