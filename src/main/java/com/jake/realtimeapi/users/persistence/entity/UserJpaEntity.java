package com.jake.realtimeapi.users.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "users")
/**
 * users 테이블과 매핑되는 JPA 엔티티.
 *
 * <p>주의:
 * domain model(User)와 persistence entity(UserJpaEntity)는 의도적으로 분리합니다.
 * - domain model: 비즈니스 규칙 중심
 * - entity: DB 매핑/영속화 중심
 */
public class UserJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "external_id", nullable = false, unique = true, length = 30)
    private String externalId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * JPA가 리플렉션으로 객체를 만들 때 사용하는 기본 생성자.
     * 접근 제한자는 protected로 두어 외부 직접 사용은 제한합니다.
     */
    protected UserJpaEntity() {
    }

    /**
     * 애플리케이션에서 명시적으로 엔티티를 만들 때 사용하는 생성자.
     */
    public UserJpaEntity(Long id, String externalId, Instant createdAt) {
        this.id = id;
        this.externalId = externalId;
        this.createdAt = createdAt;
    }

    @PrePersist
    /**
     * INSERT 직전에 호출되는 JPA 생명주기 콜백.
     * createdAt이 비어 있으면 현재 시각으로 채웁니다.
     */
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public Long getId() {
        return id;
    }

    public String getExternalId() {
        return externalId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
