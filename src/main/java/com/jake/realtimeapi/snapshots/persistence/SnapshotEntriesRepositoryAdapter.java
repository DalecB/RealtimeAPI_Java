package com.jake.realtimeapi.snapshots.persistence;

import com.jake.realtimeapi.snapshots.domain.model.SnapshotEntries;
import com.jake.realtimeapi.snapshots.domain.repository.SnapshotEntriesRepository;
import com.jake.realtimeapi.snapshots.domain.repository.command.SnapshotEntryUpsertParam;
import com.jake.realtimeapi.snapshots.persistence.repository.SpringDataSnapshotEntriesJpaRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public class SnapshotEntriesRepositoryAdapter implements SnapshotEntriesRepository {

    private static final String INSERT_SQL = """
    INSERT INTO snapshot_entries (snapshot_id, rank, user_id, score)
    VALUES (:snapshotId, :rank, :userId, :score)
    """;

    private final SpringDataSnapshotEntriesJpaRepository jpaRepository;
    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    public SnapshotEntriesRepositoryAdapter(
            SpringDataSnapshotEntriesJpaRepository jpaRepository,
            NamedParameterJdbcTemplate namedParameterJdbcTemplate
    ) {
        this.jpaRepository = jpaRepository;
        this.namedParameterJdbcTemplate = namedParameterJdbcTemplate;
    }

    @Override
    @Transactional
    public void replaceBySnapshotId(long snapshotId, List<SnapshotEntryUpsertParam> entries) {
        if (entries == null || entries.isEmpty()) {
            throw new IllegalArgumentException("entries must not be empty");
        }

        boolean hasDifferentSnapshotId = entries.stream()
                .anyMatch(e -> e.snapshotId() != snapshotId);
        if (hasDifferentSnapshotId) {
            throw new IllegalArgumentException("all entries must have the same snapshotId: " + snapshotId);
        }

        jpaRepository.deleteBySnapshotId(snapshotId);

        SqlParameterSource[] batch = entries.stream()
                .map(e -> new MapSqlParameterSource()
                        .addValue("snapshotId", snapshotId)
                        .addValue("rank", e.rank())
                        .addValue("userId", e.userId())
                        .addValue("score", e.score()))
                .toArray(SqlParameterSource[]::new);

        namedParameterJdbcTemplate.batchUpdate(INSERT_SQL, batch);
    }

    @Override
    public long countBySnapshotId(long snapshotId) {
        return jpaRepository.countBySnapshotId(snapshotId);
    }

    @Override
    public Optional<SnapshotEntries> findById(long id) {
        return jpaRepository.findById(id).map(SnapshotEntriesPersistenceMapper::toDomain);
    }

    @Override
    public Optional<SnapshotEntries> findBySnapshotIdAndUserId(long snapshotId, long userId) {
        return jpaRepository.findBySnapshotIdAndUserId(snapshotId, userId).map(SnapshotEntriesPersistenceMapper::toDomain);
    }

    @Override
    public List<SnapshotEntries> findBySnapshotId(long snapshotId, int offset, int limit) {
        return jpaRepository.findBySnapshotId(snapshotId, offset, limit).stream()
                .map(SnapshotEntriesPersistenceMapper::toDomain)
                .toList();
    }
}
