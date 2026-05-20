package com.jake.realtimeapi.snapshots.persistence;

import com.jake.realtimeapi.snapshots.domain.repository.SnapshotExecutionLockRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PostgresSnapshotExecutionLockRepository implements SnapshotExecutionLockRepository {

    private static final Logger log = LoggerFactory.getLogger(PostgresSnapshotExecutionLockRepository.class);
    private final JdbcTemplate jdbcTemplate;

    public PostgresSnapshotExecutionLockRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean tryLock(long lockKey) {
        // Postgres advisory lock: 같은 lockKey에 대해 동시 실행을 1개로 제한한다.
        Boolean locked = jdbcTemplate.queryForObject("SELECT pg_try_advisory_lock(?)", Boolean.class, lockKey);
        return Boolean.TRUE.equals(locked);
    }

    @Override
    public void unlock(long lockKey) {
        // unlock=false는 "해당 세션이 락을 들고 있지 않다"는 의미이므로 경고 로그만 남긴다.
        Boolean unlocked = jdbcTemplate.queryForObject("SELECT pg_advisory_unlock(?)", Boolean.class, lockKey);
        if (!Boolean.TRUE.equals(unlocked)) {
            log.warn("snapshots.lock unlock returned false lockKey={}", lockKey);
        }
    }
}
