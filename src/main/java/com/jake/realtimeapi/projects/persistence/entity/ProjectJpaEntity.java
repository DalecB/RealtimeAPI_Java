package com.jake.realtimeapi.projects.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "projects")
public class ProjectJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "admin_id")
    private Long adminId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "name", nullable = false, unique = true, length = 100)
    private String name;

    protected ProjectJpaEntity() {}

    public ProjectJpaEntity(UUID id, Long adminId, Instant createdAt, String name) {
        this.id = id;
        this.adminId = adminId;
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

    public Long getAdminId() {
        return adminId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getName() {
        return name;
    }
}
