package com.jake.realtimeapi.snapshots.worker;

import com.jake.realtimeapi.TestcontainersConfiguration;
import com.jake.realtimeapi.events.persistence.redis.LeaderboardRedisKeyFactory;
import com.jake.realtimeapi.leaderboards.domain.model.Leaderboard;
import com.jake.realtimeapi.leaderboards.domain.repository.LeaderboardRepository;
import com.jake.realtimeapi.projects.domain.model.Project;
import com.jake.realtimeapi.projects.domain.repository.ProjectRepository;
import com.jake.realtimeapi.snapshots.domain.repository.SnapshotExecutionLockRepository;
import com.jake.realtimeapi.support.userid.UserIdCodec;
import com.jake.realtimeapi.users.domain.model.User;
import com.jake.realtimeapi.users.domain.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 캡처 중 SQL 오류는 snapshot_entries.user_id FK 위반으로 낸다: 운영 코드나 빈을 바꾸지 않고 트랜잭션 안에서 실제 PG 오류가 난다.
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SnapshotCaptureLockLifetimeTest {

    private static final int TOP_N = 1000;
    private static final long MISSING_USER_ID = Long.MAX_VALUE;

    @Autowired
    private SnapshotCaptureExecutionService executionService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private LeaderboardRepository leaderboardRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private SnapshotExecutionLockRepository lockRepository;

    @Test
    void captureFailure_surfacesOriginalErrorInsteadOfUnlockError() {
        UUID leaderboardId = leaderboardWithMissingUserRanked();

        assertThrows(DataIntegrityViolationException.class,
                () -> executionService.execute(leaderboardId, Instant.now(), TOP_N));
    }

    @Test
    void captureFailure_leavesNoAdvisoryLock() {
        UUID leaderboardId = leaderboardWithMissingUserRanked();

        assertThrows(RuntimeException.class, () -> executionService.execute(leaderboardId, Instant.now(), TOP_N));

        assertEquals(0, advisoryLocksOn(leaderboardId));
    }

    @Test
    void captureFailure_nextCaptureOnAnotherConnectionAcquiresLock() throws Exception {
        UUID leaderboardId = leaderboardWithMissingUserRanked();
        assertThrows(RuntimeException.class, () -> executionService.execute(leaderboardId, Instant.now(), TOP_N));
        redisTemplate.opsForZSet().remove(LeaderboardRedisKeyFactory.rankingKey(leaderboardId), UserIdCodec.format(MISSING_USER_ID));

        // Hikari는 같은 스레드에 방금 반납한 연결을 다시 준다. 그 연결을 쥐고 있어야 다음 캡처가 다른 세션에서 돈다.
        SnapshotCaptureExecutionResult next;
        try (Connection ignored = dataSource.getConnection()) {
            next = CompletableFuture.supplyAsync(() -> executionService.execute(leaderboardId, Instant.now(), TOP_N)).get();
        }

        assertTrue(next.lockAcquired());
        assertEquals(false, next.captureResult().skipped());
    }

    @Test
    void captureSuccess_leavesNoAdvisoryLock() {
        UUID leaderboardId = leaderboardWithRealUserRanked();

        SnapshotCaptureExecutionResult result = executionService.execute(leaderboardId, Instant.now(), TOP_N);

        assertEquals(false, result.captureResult().skipped());
        assertEquals(0, advisoryLocksOn(leaderboardId));
    }

    @Test
    void concurrentCaptures_onlyOneProceedsUntilFirstTransactionEnds() throws Exception {
        UUID leaderboardId = leaderboardWithRealUserRanked();
        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);

        // 바깥 트랜잭션에 합류시켜 첫 캡처의 락을 커밋 전까지 붙잡아 둔다.
        CompletableFuture<SnapshotCaptureExecutionResult> first = CompletableFuture.supplyAsync(() ->
                new TransactionTemplate(transactionManager).execute(status -> {
                    SnapshotCaptureExecutionResult result = executionService.execute(leaderboardId, Instant.now(), TOP_N);
                    firstLocked.countDown();
                    awaitQuietly(releaseFirst);
                    return result;
                }));
        assertTrue(firstLocked.await(10, TimeUnit.SECONDS));

        SnapshotCaptureExecutionResult second = executionService.execute(leaderboardId, Instant.now(), TOP_N);
        releaseFirst.countDown();

        assertTrue(first.get(10, TimeUnit.SECONDS).lockAcquired());
        assertFalse(second.lockAcquired());
        assertEquals(0, advisoryLocksOn(leaderboardId));
        assertTrue(executionService.execute(leaderboardId, Instant.now(), TOP_N).lockAcquired());
    }

    @Test
    void tryLockOutsideTransaction_failsFast() {
        long lockKey = SnapshotCaptureExecutionService.lockKey(UUID.randomUUID());

        assertThrows(IllegalTransactionStateException.class, () -> lockRepository.tryLockForCurrentTransaction(lockKey));
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private UUID leaderboardWithMissingUserRanked() {
        UUID leaderboardId = leaderboardWithRealUserRanked();
        redisTemplate.opsForZSet().add(LeaderboardRedisKeyFactory.rankingKey(leaderboardId), UserIdCodec.format(MISSING_USER_ID), 50.0);
        return leaderboardId;
    }

    private UUID leaderboardWithRealUserRanked() {
        String runToken = UUID.randomUUID().toString().substring(0, 8);
        User user = userRepository.save(User.newUser("lock-" + runToken));
        Project project = projectRepository.save(Project.newProject("lock-proj-" + UUID.randomUUID(), user.id()));
        Leaderboard leaderboard = leaderboardRepository.save(Leaderboard.newLeaderboard(project.id(), "lock-board"));
        redisTemplate.opsForZSet().add(LeaderboardRedisKeyFactory.rankingKey(leaderboard.id()), UserIdCodec.format(user.id()), 100.0);
        return leaderboard.id();
    }

    // bigint 키 advisory lock은 pg_locks에서 상위 32비트가 classid, 하위 32비트가 objid, objsubid=1로 잡힌다.
    private int advisoryLocksOn(UUID leaderboardId) {
        long lockKey = SnapshotCaptureExecutionService.lockKey(leaderboardId);
        Integer count = jdbcTemplate.queryForObject("""
                SELECT count(*)::int FROM pg_locks
                WHERE locktype = 'advisory' AND objsubid = 1 AND classid::bigint = ? AND objid::bigint = ?
                """, Integer.class, lockKey >>> 32, lockKey & 0xFFFF_FFFFL);
        return count == null ? 0 : count;
    }
}
