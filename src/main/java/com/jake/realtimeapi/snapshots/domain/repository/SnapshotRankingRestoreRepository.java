package com.jake.realtimeapi.snapshots.domain.repository;

import com.jake.realtimeapi.snapshots.domain.model.SnapshotRankingRow;

import java.util.List;
import java.util.UUID;

public interface SnapshotRankingRestoreRepository {

    long countRanks(UUID leaderboardId);

    void replaceTopRanks(UUID leaderboardId, List<SnapshotRankingRow> rows);
}
