package com.jake.realtimeapi.leaderboards.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "leaderboards")
public class LeaderboardJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "name", nullable = false)
    private String name;

    protected LeaderboardJpaEntity() {}

    public LeaderboardJpaEntity(UUID id, UUID projectId, String name, Instant createdAt) {
        this.id = id;
        this.projectId = projectId;
        this.createdAt = createdAt;
        this.name = name;
    }

    @PrePersist
    void onCreate() {
        if(createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getName() {
        return name;
    }
}
