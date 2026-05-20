package com.jake.realtimeapi.leaderboards.domain.repository;

import com.jake.realtimeapi.leaderboards.domain.model.Leaderboard;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LeaderboardRepository {

    Leaderboard save(Leaderboard leaderboard);

    boolean existsByName(UUID projectId, String name);

    Optional<Leaderboard> findById(UUID leaderboardId);

    Optional<Leaderboard> findByName(UUID projectId, String name);

    List<Leaderboard> findByProjectId(UUID projectId);

    // Snapshot worker가 전체 leaderboard를 순회할 때 사용한다.
    List<UUID> findAllIds();

    long count();
}
