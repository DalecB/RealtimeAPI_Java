package com.jake.realtimeapi.events.domain.repository;

import com.jake.realtimeapi.events.domain.model.TopRankItem;
import com.jake.realtimeapi.events.domain.model.UserRankResult;

import java.util.List;
import java.util.UUID;

public interface RankingQueryRepository {

    List<TopRankItem> findTopByLeaderboardId(UUID leaderboardId, int offset, int limit);

    UserRankResult findUserRank(UUID leaderboardId, long userId);

    long countParticipants(UUID leaderboardId);
}
