package com.jake.realtimeapi.snapshots.worker;

import com.jake.realtimeapi.TestcontainersConfiguration;
import com.jake.realtimeapi.events.persistence.redis.LeaderboardRedisKeyFactory;
import com.jake.realtimeapi.infra.circuitbreaker.RedisHotPathCircuitBreaker;
import com.jake.realtimeapi.leaderboards.domain.model.Leaderboard;
import com.jake.realtimeapi.leaderboards.domain.repository.LeaderboardRepository;
import com.jake.realtimeapi.projects.domain.model.Project;
import com.jake.realtimeapi.projects.domain.repository.ProjectRepository;
import com.jake.realtimeapi.snapshots.application.command.CaptureSnapshotCommand;
import com.jake.realtimeapi.snapshots.application.usecase.CaptureSnapshotUseCase;
import com.jake.realtimeapi.snapshots.domain.model.SnapshotRankingRow;
import com.jake.realtimeapi.snapshots.domain.repository.SnapshotRankingRestoreRepository;
import com.jake.realtimeapi.snapshots.persistence.redis.RedisSnapshotRankingRestoreRepository;
import com.jake.realtimeapi.support.userid.UserIdCodec;
import com.jake.realtimeapi.users.domain.model.User;
import com.jake.realtimeapi.users.domain.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

// Tomcat은 refresh 중에 이미 포트를 연다. 복구 중 요청을 막는 것은 readiness뿐이라 HTTP로 직접 확인한다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfiguration.class, ColdStartRecoveryReadinessTest.TimelineConfig.class})
class ColdStartRecoveryReadinessTest {

    @Autowired
    private Timeline timeline;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void readinessAcceptsTrafficOnlyAfterColdStartRecoveryRestoredRedis() {
        assertEquals(List.of(
                "restore readiness=REFUSING_TRAFFIC http=503 breaker=OPEN",
                "ACCEPTING_TRAFFIC zcard=3 breaker=CLOSED"
        ), timeline.events);

        assertEquals(HttpStatus.OK, restTemplate.getForEntity("/actuator/health/readiness", String.class).getStatusCode());
    }

    static class Timeline {
        final List<String> events = new CopyOnWriteArrayList<>();
        volatile UUID leaderboardId;
    }

    static class SeedSnapshotBeforeRecovery implements ApplicationRunner, Ordered {

        private final Timeline timeline;
        private final UserRepository userRepository;
        private final ProjectRepository projectRepository;
        private final LeaderboardRepository leaderboardRepository;
        private final CaptureSnapshotUseCase captureSnapshotUseCase;
        private final StringRedisTemplate redisTemplate;

        SeedSnapshotBeforeRecovery(
                Timeline timeline,
                UserRepository userRepository,
                ProjectRepository projectRepository,
                LeaderboardRepository leaderboardRepository,
                CaptureSnapshotUseCase captureSnapshotUseCase,
                StringRedisTemplate redisTemplate
        ) {
            this.timeline = timeline;
            this.userRepository = userRepository;
            this.projectRepository = projectRepository;
            this.leaderboardRepository = leaderboardRepository;
            this.captureSnapshotUseCase = captureSnapshotUseCase;
            this.redisTemplate = redisTemplate;
        }

        @Override
        public void run(ApplicationArguments args) {
            String runToken = UUID.randomUUID().toString().substring(0, 8);
            List<User> users = IntStream.range(0, 3)
                    .mapToObj(i -> userRepository.save(User.newUser("cold-" + runToken + "-" + i)))
                    .toList();
            Project project = projectRepository.save(Project.newProject("cold-proj-" + runToken, users.get(0).id()));
            Leaderboard leaderboard = leaderboardRepository.save(Leaderboard.newLeaderboard(project.id(), "cold-board"));
            String rankingKey = LeaderboardRedisKeyFactory.rankingKey(leaderboard.id());

            long score = 300L;
            for (User user : users) {
                redisTemplate.opsForZSet().add(rankingKey, UserIdCodec.format(user.id()), (double) score);
                score -= 100L;
            }
            captureSnapshotUseCase.capture(new CaptureSnapshotCommand(leaderboard.id(), Instant.now(), 1000));
            redisTemplate.delete(rankingKey);
            timeline.leaderboardId = leaderboard.id();
        }

        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE;
        }
    }

    static class ReadinessProbingRestoreRepository implements SnapshotRankingRestoreRepository {

        private final SnapshotRankingRestoreRepository delegate;
        private final Timeline timeline;
        private final ApplicationAvailability availability;
        private final RedisHotPathCircuitBreaker breaker;
        private final Environment environment;

        ReadinessProbingRestoreRepository(
                SnapshotRankingRestoreRepository delegate,
                Timeline timeline,
                ApplicationAvailability availability,
                RedisHotPathCircuitBreaker breaker,
                Environment environment
        ) {
            this.delegate = delegate;
            this.timeline = timeline;
            this.availability = availability;
            this.breaker = breaker;
            this.environment = environment;
        }

        @Override
        public long countRanks(UUID leaderboardId) {
            return delegate.countRanks(leaderboardId);
        }

        @Override
        public void replaceTopRanks(UUID leaderboardId, List<SnapshotRankingRow> rows) {
            timeline.events.add("restore readiness=" + availability.getReadinessState()
                    + " http=" + readinessHttpStatus()
                    + " breaker=" + breaker.getStatus().state());
            delegate.replaceTopRanks(leaderboardId, rows);
        }

        private int readinessHttpStatus() {
            URI uri = URI.create("http://localhost:" + environment.getProperty("local.server.port") + "/actuator/health/readiness");
            try {
                return HttpClient.newHttpClient()
                        .send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.discarding())
                        .statusCode();
            } catch (IOException | InterruptedException exception) {
                throw new IllegalStateException(exception);
            }
        }
    }

    static class ReadinessRecorder {

        private final Timeline timeline;
        private final StringRedisTemplate redisTemplate;
        private final RedisHotPathCircuitBreaker breaker;

        ReadinessRecorder(Timeline timeline, StringRedisTemplate redisTemplate, RedisHotPathCircuitBreaker breaker) {
            this.timeline = timeline;
            this.redisTemplate = redisTemplate;
            this.breaker = breaker;
        }

        @EventListener
        void onReadiness(AvailabilityChangeEvent<ReadinessState> event) {
            if (event.getState() != ReadinessState.ACCEPTING_TRAFFIC) {
                return;
            }
            Long zcard = redisTemplate.opsForZSet().zCard(LeaderboardRedisKeyFactory.rankingKey(timeline.leaderboardId));
            timeline.events.add(event.getState() + " zcard=" + zcard + " breaker=" + breaker.getStatus().state());
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TimelineConfig {

        @Bean
        Timeline timeline() {
            return new Timeline();
        }

        @Bean
        SeedSnapshotBeforeRecovery seedSnapshotBeforeRecovery(
                Timeline timeline,
                UserRepository userRepository,
                ProjectRepository projectRepository,
                LeaderboardRepository leaderboardRepository,
                CaptureSnapshotUseCase captureSnapshotUseCase,
                StringRedisTemplate redisTemplate
        ) {
            return new SeedSnapshotBeforeRecovery(
                    timeline, userRepository, projectRepository, leaderboardRepository, captureSnapshotUseCase, redisTemplate);
        }

        @Bean
        @Primary
        SnapshotRankingRestoreRepository readinessProbingRestoreRepository(
                RedisSnapshotRankingRestoreRepository delegate,
                Timeline timeline,
                ApplicationAvailability availability,
                RedisHotPathCircuitBreaker breaker,
                Environment environment
        ) {
            return new ReadinessProbingRestoreRepository(delegate, timeline, availability, breaker, environment);
        }

        @Bean
        ReadinessRecorder readinessRecorder(Timeline timeline, StringRedisTemplate redisTemplate, RedisHotPathCircuitBreaker breaker) {
            return new ReadinessRecorder(timeline, redisTemplate, breaker);
        }
    }
}
