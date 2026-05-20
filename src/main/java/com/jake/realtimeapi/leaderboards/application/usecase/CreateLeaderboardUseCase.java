package com.jake.realtimeapi.leaderboards.application.usecase;

import com.jake.realtimeapi.leaderboards.application.command.CreateLeaderboardCommand;
import com.jake.realtimeapi.leaderboards.domain.model.Leaderboard;

public interface CreateLeaderboardUseCase {

    Leaderboard create(CreateLeaderboardCommand command);
}
