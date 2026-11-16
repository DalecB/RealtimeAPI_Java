package com.jake.realtimeapi.events.domain.repository;

import com.jake.realtimeapi.events.domain.model.RankingSnapshot;

import java.util.Optional;
import java.util.UUID;

/** Redis를 못 읽을 때 쓰는 PostgreSQL 최신 스냅샷 조회 포트. */
public interface RankingSnapshotRepository {

    Optional<RankingSnapshot> findLatest(UUID leaderboardId);
}
