package com.jake.realtimeapi.snapshots.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "snapshot_batches")
public class SnapshotBatchesJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "leaderboard_id", nullable = false)
    private UUID leaderboardId;

    @Column(name = "snapshot_at", nullable = false)
    private Instant snapshotAt;

    @Column(name = "top_n", nullable = false)
    private Integer topN;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected SnapshotBatchesJpaEntity() {}

    public SnapshotBatchesJpaEntity(Long id, UUID leaderboardId, Instant snapshotAt, Integer topN, Instant createdAt) {
        this.id = id;
        this.leaderboardId = leaderboardId;
        this.snapshotAt = snapshotAt;
        this.topN = topN;
        this.createdAt = createdAt;
    }

    @PrePersist
    void onCreate() {
        if(createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public Long getId() {
        return id;
    }

    public UUID getLeaderboardId() {
        return leaderboardId;
    }

    public Instant getSnapshotAt() {
        return snapshotAt;
    }

    public Integer getTopN() {
        return topN;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
