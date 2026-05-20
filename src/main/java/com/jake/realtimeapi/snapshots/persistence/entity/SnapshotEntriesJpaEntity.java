package com.jake.realtimeapi.snapshots.persistence.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.Positive;

import java.time.Instant;

@Entity
@Table(name = "snapshot_entries")
public class SnapshotEntriesJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "snapshot_id", nullable = false)
    private Long snapshotId;

    @Positive
    @Column(name = "rank", nullable = false)
    private Integer rank;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Positive
    @Column(name = "score", nullable = false)
    private Long score;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected SnapshotEntriesJpaEntity() {}

    public SnapshotEntriesJpaEntity(Long id, Long snapshotId, Integer rank, Long userId, Long score, Instant createdAt) {
        this.id = id;
        this.snapshotId = snapshotId;
        this.rank = rank;
        this.userId = userId;
        this.score = score;
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

    public Long getSnapshotId() {
        return snapshotId;
    }

    public Integer getRank() {
        return rank;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getScore() {
        return score;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
