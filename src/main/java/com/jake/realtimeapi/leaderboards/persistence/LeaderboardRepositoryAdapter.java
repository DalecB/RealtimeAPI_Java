package com.jake.realtimeapi.leaderboards.persistence;

import com.jake.realtimeapi.leaderboards.domain.model.Leaderboard;
import com.jake.realtimeapi.leaderboards.domain.repository.LeaderboardRepository;
import com.jake.realtimeapi.leaderboards.persistence.repository.SpringDataLeaderboardJpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class LeaderboardRepositoryAdapter implements LeaderboardRepository {

    private final SpringDataLeaderboardJpaRepository jpaRepository;

    public LeaderboardRepositoryAdapter(SpringDataLeaderboardJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Leaderboard save(Leaderboard leaderboard) {
        return LeaderboardPersistenceMapper.toDomain(jpaRepository.save(LeaderboardPersistenceMapper.toEntity(leaderboard)));
    }

    @Override
    public boolean existsByName(UUID projectId, String name) {
        return jpaRepository.existsByProjectIdAndName(projectId, name);
    }

    @Override
    public Optional<Leaderboard> findById(UUID id) {
        return jpaRepository.findById(id).map(LeaderboardPersistenceMapper::toDomain);
    }

    @Override
    public Optional<Leaderboard> findByName(UUID projectId, String name) {
        return jpaRepository.findByProjectIdAndName(projectId, name).map(LeaderboardPersistenceMapper::toDomain);
    }

    @Override
    public List<Leaderboard> findByProjectId(UUID projectId) {
        return jpaRepository.findByProjectId(projectId).stream()
                .map(LeaderboardPersistenceMapper::toDomain)
                .toList();
    }

    @Override
    public List<UUID> findAllIds() {
        return jpaRepository.findAllIds();
    }

    @Override
    public long count() {
        return jpaRepository.count();
    }
}
