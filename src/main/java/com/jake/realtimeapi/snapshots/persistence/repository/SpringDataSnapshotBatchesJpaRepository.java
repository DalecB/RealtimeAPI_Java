package com.jake.realtimeapi.snapshots.persistence.repository;

import com.jake.realtimeapi.snapshots.persistence.entity.SnapshotBatchesJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SpringDataSnapshotBatchesJpaRepository extends JpaRepository<SnapshotBatchesJpaEntity, Long> {

    Optional<SnapshotBatchesJpaEntity> findFirstByLeaderboardIdOrderBySnapshotAtDesc(UUID leaderboardId);

    List<SnapshotBatchesJpaEntity> findByLeaderboardIdOrderBySnapshotAtDesc(UUID leaderboardId);

    Optional<SnapshotBatchesJpaEntity> findByLeaderboardIdAndSnapshotAt(UUID leaderboardId, Instant snapshotAt);
}
