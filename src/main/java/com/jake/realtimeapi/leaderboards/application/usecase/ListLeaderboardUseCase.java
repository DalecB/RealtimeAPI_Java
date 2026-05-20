package com.jake.realtimeapi.leaderboards.application.usecase;

import com.jake.realtimeapi.leaderboards.domain.model.Leaderboard;

import java.util.List;
import java.util.UUID;

public interface ListLeaderboardUseCase {

    List<Leaderboard> getByProjectId(UUID projectId);
}
