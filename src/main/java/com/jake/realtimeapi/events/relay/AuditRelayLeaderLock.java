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

// Hikari 연결에 세션 락을 잡으면 close()가 풀로 반납할 뿐 세션이 살아 락이 남으므로 풀 밖 전용 연결을 쓴다.
@Component
@ConditionalOnProperty(name = "events.relay.enabled", havingValue = "true")
public class AuditRelayLeaderLock {

    private static final Logger log = LoggerFactory.getLogger(AuditRelayLeaderLock.class);

    // 두 int 키는 스냅샷이 쓰는 bigint 키와 키 공간이 겹치지 않는다(pg_locks.objsubid 2 vs 1).
    static final int LOCK_NAMESPACE = 1;
    static final int RELAY_LOCK_ID = 1;

    private final JdbcConnectionDetails db;
    private Connection connection;
    private volatile boolean leader;

    public AuditRelayLeaderLock(JdbcConnectionDetails db, MeterRegistry meterRegistry) {
        this.db = db;
        Gauge.builder("audit_relay_leader", this, lock -> lock.leader ? 1 : 0).register(meterRegistry);
    }

    public synchronized boolean isLeader() {
        try {
            if (connection == null || !connection.isValid(1)) {
                release();
                connection = DriverManager.getConnection(db.getJdbcUrl(), connectionProperties());
            }
            if (!leader) {
                // 세션 락은 재진입돼 매 틱 다시 잡으면 획득 횟수가 쌓이므로, 쥐고 있는 동안은 부르지 않는다.
                leader = tryLock(connection);
            }
        } catch (SQLException ex) {
            log.warn("relay leader check failed ({})", ex.getMessage());
            release();
        }
        return leader;
    }

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
