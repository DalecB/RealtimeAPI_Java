package com.jake.realtimeapi.events.relay;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.JdbcConnectionDetails;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Properties;

/**
 * 여러 인스턴스 중 릴레이를 실제로 돌릴 하나를 PostgreSQL advisory lock으로 고른다.
 *
 * <p>세션 락을 <b>풀 밖의 전용 연결</b>에 잡는다. 락은 이 연결(세션)이 살아 있는 동안 유지되고,
 * 프로세스가 죽어 연결이 끊기면 PostgreSQL이 즉시 풀어 다른 인스턴스가 다음 틱에 잡는다.
 * Hikari 연결에 잡으면 close()가 연결을 풀로 돌려줄 뿐 세션은 살아 있어 락이 남는다.
 */
@Component
@ConditionalOnProperty(name = "events.relay.enabled", havingValue = "true")
public class AuditRelayLeaderLock {

    private static final Logger log = LoggerFactory.getLogger(AuditRelayLeaderLock.class);

    // 두 int 키는 스냅샷이 쓰는 bigint 키와 키 공간이 겹치지 않는다(pg_locks.objsubid 2 vs 1).
    // 첫 값은 이 앱의 두 int 락 네임스페이스, 둘째 값은 그 안의 릴레이 락이다.
    static final int LOCK_NAMESPACE = 1;
    static final int RELAY_LOCK_ID = 1;

    private final JdbcConnectionDetails db;
    private Connection connection;
    private volatile boolean leader;

    public AuditRelayLeaderLock(JdbcConnectionDetails db, MeterRegistry meterRegistry) {
        this.db = db;
        Gauge.builder("audit_relay_leader", this, lock -> lock.leader ? 1 : 0).register(meterRegistry);
    }

    /** 이번 틱에 릴레이해도 되는지. 연결이 끊겼으면 다시 연결해 락을 다시 시도한다. */
    public synchronized boolean isLeader() {
        try {
            if (connection == null || !connection.isValid(1)) {
                // 세션이 죽었으면 그 세션의 락도 이미 풀렸다. 새 세션에서 처음부터 다시 잡는다.
                release();
                connection = DriverManager.getConnection(db.getJdbcUrl(), connectionProperties());
            }
            if (!leader) {
                // 세션 락은 재진입된다. 이미 쥐고 있으면 다시 호출하지 않아 획득 횟수가 쌓이지 않게 한다.
                leader = tryLock(connection);
            }
        } catch (SQLException ex) {
            log.warn("relay leader check failed ({})", ex.getMessage());
            release();
        }
        return leader;
    }

    /** 연결을 닫아 락을 놓는다. 종료 시 다른 인스턴스가 다음 틱에 바로 이어받게 한다. */
    @PreDestroy
    public synchronized void release() {
        leader = false;
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException ex) {
            log.debug("relay leader connection close failed ({})", ex.getMessage());
        }
        connection = null;
    }

    private boolean tryLock(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_try_advisory_lock(?, ?)")) {
            statement.setInt(1, LOCK_NAMESPACE);
            statement.setInt(2, RELAY_LOCK_ID);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getBoolean(1);
            }
        }
    }

    private Properties connectionProperties() {
        Properties properties = new Properties();
        properties.setProperty("user", db.getUsername());
        properties.setProperty("password", db.getPassword());
        // 스케줄러 스레드를 무한정 붙잡지 않게 연결·조회 대기에 상한을 둔다.
        properties.setProperty("loginTimeout", "5");
        properties.setProperty("socketTimeout", "10");
        return properties;
    }
}
