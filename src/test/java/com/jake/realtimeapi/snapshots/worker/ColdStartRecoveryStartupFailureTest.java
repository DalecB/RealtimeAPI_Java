package com.jake.realtimeapi.snapshots.worker;

import com.jake.realtimeapi.RealtimeApiApplication;
import com.jake.realtimeapi.leaderboards.domain.model.Leaderboard;
import com.jake.realtimeapi.leaderboards.domain.repository.LeaderboardRepository;
import com.jake.realtimeapi.projects.domain.model.Project;
import com.jake.realtimeapi.projects.domain.repository.ProjectRepository;
import com.jake.realtimeapi.users.domain.model.User;
import com.jake.realtimeapi.users.domain.repository.UserRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 리더보드가 하나도 없으면 복구가 Redis를 건드리지 않아 기동이 성공하므로, 러너보다 먼저 리더보드를 심는다.
@Testcontainers
class ColdStartRecoveryStartupFailureTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:latest"));

    @Container
    static final KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("apache/kafka-native:3.8.0"));

    private static int closedRedisPort;

    @BeforeAll
    static void reserveClosedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            closedRedisPort = socket.getLocalPort();
        }
    }

    @Test
    void redisDownAtStartup_failsStartupWhenRecoveryEnabled() {
        RedisConnectionFailureException exception =
                assertThrows(RedisConnectionFailureException.class, () -> start(true).close());

        assertTrue(Arrays.stream(exception.getStackTrace())
                .anyMatch(frame -> frame.getClassName().equals(ColdStartRecoveryRunner.class.getName())));
    }

    @Test
    void redisDownAtStartup_startsWhenRecoveryDisabled() {
        try (ConfigurableApplicationContext context = start(false)) {
            assertTrue(context.getBeansOfType(ColdStartRecoveryRunner.class).isEmpty());
            assertEquals(ReadinessState.ACCEPTING_TRAFFIC, context.getBean(ApplicationAvailability.class).getReadinessState());
        }
    }

    private ConfigurableApplicationContext start(boolean recoveryEnabled) {
        return new SpringApplicationBuilder(RealtimeApiApplication.class, SeedLeaderboardBeforeRecovery.class)
                .run(
                        "--server.port=0",
                        "--spring.datasource.url=" + postgres.getJdbcUrl(),
                        "--spring.datasource.username=" + postgres.getUsername(),
                        "--spring.datasource.password=" + postgres.getPassword(),
                        "--spring.kafka.bootstrap-servers=" + kafka.getBootstrapServers(),
                        "--spring.data.redis.host=localhost",
                        "--spring.data.redis.port=" + closedRedisPort,
                        "--snapshots.recovery.enabled=" + recoveryEnabled
                );
    }

    static class SeedLeaderboardBeforeRecovery implements ApplicationRunner, Ordered {

        private final UserRepository userRepository;
        private final ProjectRepository projectRepository;
        private final LeaderboardRepository leaderboardRepository;

        SeedLeaderboardBeforeRecovery(
                UserRepository userRepository,
                ProjectRepository projectRepository,
                LeaderboardRepository leaderboardRepository
        ) {
            this.userRepository = userRepository;
            this.projectRepository = projectRepository;
            this.leaderboardRepository = leaderboardRepository;
        }

        @Override
        public void run(ApplicationArguments args) {
            String runToken = UUID.randomUUID().toString().substring(0, 8);
            User owner = userRepository.save(User.newUser("down-" + runToken));
            Project project = projectRepository.save(Project.newProject("down-proj-" + runToken, owner.id()));
            leaderboardRepository.save(Leaderboard.newLeaderboard(project.id(), "down-board"));
        }

        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE;
        }
    }
}
