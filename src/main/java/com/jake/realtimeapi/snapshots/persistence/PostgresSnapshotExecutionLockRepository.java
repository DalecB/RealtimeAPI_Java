package com.jake.realtimeapi.snapshots.persistence;

import com.jake.realtimeapi.snapshots.domain.repository.SnapshotExecutionLockRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Repository
public class PostgresSnapshotExecutionLockRepository implements SnapshotExecutionLockRepository {

    private final JdbcTemplate jdbcTemplate;

    public PostgresSnapshotExecutionLockRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean tryLockForCurrentTransaction(long lockKey) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalTransactionStateException("snapshot execution lock requires an active transaction");
        }
        // Redis SET NX 대신 PG advisory lock을 쓴다: Redis 장애 때 Cold Path까지 멈추지 않게 한다(PRD ADR-003).
        // 세션 락은 트랜잭션이 abort되면 unlock이 25P02로 실패해 풀 연결에 남는다. xact 락은 커밋·롤백이 푼다.
        Boolean locked = jdbcTemplate.queryForObject("SELECT pg_try_advisory_xact_lock(?)", Boolean.class, lockKey);
        return Boolean.TRUE.equals(locked);
    }
}
