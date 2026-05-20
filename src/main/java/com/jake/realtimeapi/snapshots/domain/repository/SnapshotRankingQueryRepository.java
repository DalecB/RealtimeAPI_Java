package com.jake.realtimeapi.snapshots.domain.repository;

import com.jake.realtimeapi.snapshots.domain.model.SnapshotRankingRow;

import java.util.List;
import java.util.UUID;

public interface SnapshotRankingQueryRepository {

    List<SnapshotRankingRow> findTopRanks(UUID leaderboardId, int limit);
}
