package com.jake.realtimeapi.snapshots.application.usecase;

import com.jake.realtimeapi.snapshots.domain.model.SnapshotRankingRow;

import java.util.List;
import java.util.UUID;

public interface FindTopRanksUseCase {

    List<SnapshotRankingRow> findTopRanks(UUID leaderboardId, int limit);
}
