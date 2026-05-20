package com.jake.realtimeapi.leaderboards.persistence;

import com.jake.realtimeapi.leaderboards.domain.model.Leaderboard;
import com.jake.realtimeapi.leaderboards.persistence.entity.LeaderboardJpaEntity;

public class LeaderboardPersistenceMapper {

    private LeaderboardPersistenceMapper() {}

    public static Leaderboard toDomain(LeaderboardJpaEntity entity) {
        return new Leaderboard(entity.getId(), entity.getProjectId(), entity.getName(), entity.getCreatedAt());
    }

    public static LeaderboardJpaEntity toEntity(Leaderboard leaderboard) {
        return new LeaderboardJpaEntity(leaderboard.id(), leaderboard.projectId(), leaderboard.name(), leaderboard.createdAt());
    }
}
