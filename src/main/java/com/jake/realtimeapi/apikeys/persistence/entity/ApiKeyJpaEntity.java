package com.jake.realtimeapi.apikeys.persistence.entity;

import com.jake.realtimeapi.apikeys.domain.model.ApiKeyStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "api_keys")
public class ApiKeyJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "key_hash", nullable = false, unique = true)
    private String keyHash;

    @Column(name = "key_prefix", nullable = false)
    private String keyPrefix;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ApiKeyStatus status;

    @Column(name = "rate_limit_per_sec", nullable = false)
    private Integer rateLimitPerSec;

    @Column(name = "daily_quota", nullable = false)
    private Integer dailyQuota;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    protected ApiKeyJpaEntity() {
    }

    public ApiKeyJpaEntity(
            Long id,
            UUID projectId,
            String keyHash,
            String keyPrefix,
            ApiKeyStatus status,
            Integer rateLimitPerSec,
            Integer dailyQuota,
            Instant createdAt,
            Instant updatedAt,
            Instant revokedAt,
            Instant expiresAt
    ) {
        this.id = id;
        this.projectId = projectId;
        this.keyHash = keyHash;
        this.keyPrefix = keyPrefix;
        this.status = status;
        this.rateLimitPerSec = rateLimitPerSec;
        this.dailyQuota = dailyQuota;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.revokedAt = revokedAt;
        this.expiresAt = expiresAt;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public String getKeyHash() {
        return keyHash;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public ApiKeyStatus getStatus() {
        return status;
    }

    public Integer getRateLimitPerSec() {
        return rateLimitPerSec;
    }

    public Integer getDailyQuota() {
        return dailyQuota;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
