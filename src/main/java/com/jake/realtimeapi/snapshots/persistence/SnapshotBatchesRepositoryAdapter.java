package com.jake.realtimeapi.snapshots.persistence;

import com.jake.realtimeapi.snapshots.domain.model.SnapshotBatches;
import com.jake.realtimeapi.snapshots.domain.repository.SnapshotBatchesRepository;
import com.jake.realtimeapi.snapshots.domain.repository.command.SnapshotBatchUpsertParam;
import com.jake.realtimeapi.snapshots.persistence.repository.SpringDataSnapshotBatchesJpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class SnapshotBatchesRepositoryAdapter implements SnapshotBatchesRepository {

    private static final String UPSERT_SQL = """
        INSERT INTO snapshot_batches (leaderboard_id, snapshot_at, top_n)
        VALUES (:leaderboardId, :snapshotAt, :topN)
        ON CONFLICT (leaderboard_id, snapshot_at)
        DO UPDATE SET top_n = EXCLUDED.top_n
        RETURNING id
    """;

    private final SpringDataSnapshotBatchesJpaRepository jpaRepository;
    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    public SnapshotBatchesRepositoryAdapter(
            SpringDataSnapshotBatchesJpaRepository jpaRepository,
            NamedParameterJdbcTemplate namedParameterJdbcTemplate
    ) {
        this.jpaRepository = jpaRepository;
        this.namedParameterJdbcTemplate = namedParameterJdbcTemplate;
    }

    @Override
    @Transactional
    public long upsert(SnapshotBatchUpsertParam param) {
        Long snapshotId = namedParameterJdbcTemplate.queryForObject(
                UPSERT_SQL,
                new MapSqlParameterSource()
                        .addValue("leaderboardId", param.leaderboardId())
                        // JDBC cannot infer Instant for native SQL here, so bind it as SQL timestamp explicitly.
                        .addValue("snapshotAt", Timestamp.from(param.snapshotAt()), Types.TIMESTAMP)
                        .addValue("topN", param.topN()),
                Long.class
        );

        if (snapshotId == null) {
            throw new IllegalStateException("snapshot_batches upsert did not return id");
        }
        return snapshotId;
    }

    @Override
    public Optional<SnapshotBatches> findById(long id) {
        return jpaRepository.findById(id).map(SnapshotBatchesPersistenceMapper::toDomain);
    }

    @Override
    public Optional<SnapshotBatches> findLatestByLeaderboardId(UUID leaderboardId) {
        return jpaRepository.findFirstByLeaderboardIdOrderBySnapshotAtDesc(leaderboardId)
                .map(SnapshotBatchesPersistenceMapper::toDomain);
    }

    @Override
    public List<SnapshotBatches> findByLeaderboardIdOrderBySnapshotAtDesc(UUID leaderboardId) {
        return jpaRepository.findByLeaderboardIdOrderBySnapshotAtDesc(leaderboardId).stream()
                .map(SnapshotBatchesPersistenceMapper::toDomain)
                .toList();
    }

    @Override
    public Optional<SnapshotBatches> findByLeaderboardIdAndSnapshotAt(UUID leaderboardId, Instant snapshotAt) {
        return jpaRepository.findByLeaderboardIdAndSnapshotAt(leaderboardId, snapshotAt).map(SnapshotBatchesPersistenceMapper::toDomain);
    }
}
