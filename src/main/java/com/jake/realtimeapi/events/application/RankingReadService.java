package com.jake.realtimeapi.events.application;

import com.jake.realtimeapi.events.application.query.GetTopRanksQuery;
import com.jake.realtimeapi.events.application.query.GetUserRankQuery;
import com.jake.realtimeapi.events.application.usecase.GetTopRanksUseCase;
import com.jake.realtimeapi.events.application.usecase.GetUserRankUseCase;
import com.jake.realtimeapi.events.domain.model.RankingSnapshot;
import com.jake.realtimeapi.events.domain.model.RankingSource;
import com.jake.realtimeapi.events.domain.model.TopRankItem;
import com.jake.realtimeapi.events.domain.model.TopRanksResult;
import com.jake.realtimeapi.events.domain.model.UserRankResult;
import com.jake.realtimeapi.events.domain.repository.RankingQueryRepository;
import com.jake.realtimeapi.events.domain.repository.RankingSnapshotRepository;
import com.jake.realtimeapi.infra.circuitbreaker.RankingReadUnavailableException;
import com.jake.realtimeapi.infra.circuitbreaker.RankingReadUnavailableException.Reason;
import com.jake.realtimeapi.infra.circuitbreaker.RedisReadPathCircuitBreaker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 랭킹 읽기. Redis를 읽기 브레이커로 감싸고, 못 읽으면 PostgreSQL 최신 스냅샷으로 답한다.
 * 스냅샷으로 정확히 답할 수 없는 요청(너무 오래됨, Top-N 밖)은 틀린 값 대신 503을 준다.
 */
@Service
public class RankingReadService implements GetTopRanksUseCase, GetUserRankUseCase {

    // 스냅샷 주기(30초)와 같다. Redis 장애 중에는 스냅샷이 갱신되지 않으므로 캐시가 항상 최신이다.
    private static final Duration SNAPSHOT_CACHE_TTL = Duration.ofSeconds(30);

    private final RankingQueryRepository rankingQueryRepository;
    private final RankingSnapshotRepository rankingSnapshotRepository;
    private final RedisReadPathCircuitBreaker readPathCircuitBreaker;
    private final Duration maxSnapshotAge;
    private final Clock clock;
    // ponytail: 리더보드 수만큼만 자라는 맵. 리더보드가 수만 개가 되면 크기 제한 캐시로 바꾼다.
    private final Map<UUID, CachedSnapshot> snapshotCache = new ConcurrentHashMap<>();

    public RankingReadService(
            RankingQueryRepository rankingQueryRepository,
            RankingSnapshotRepository rankingSnapshotRepository,
            RedisReadPathCircuitBreaker readPathCircuitBreaker,
            @Value("${rankings.fallback.max-snapshot-age:10m}") Duration maxSnapshotAge,
            Clock clock
    ) {
        this.rankingQueryRepository = rankingQueryRepository;
        this.rankingSnapshotRepository = rankingSnapshotRepository;
        this.readPathCircuitBreaker = readPathCircuitBreaker;
        this.maxSnapshotAge = maxSnapshotAge;
        this.clock = clock;
    }

    @Override
    public TopRanksResult getTopRanks(GetTopRanksQuery query) {
        return readPathCircuitBreaker.execute(
                () -> {
                    List<TopRankItem> items = rankingQueryRepository.findTopByLeaderboardId(
                            query.leaderboardId(), query.offset(), query.limit());
                    long total = rankingQueryRepository.countParticipants(query.leaderboardId());
                    return new TopRanksResult(query.leaderboardId(), items, total);
                },
                () -> topRanksFromSnapshot(query)
        );
    }

    @Override
    public UserRankResult getUserRank(GetUserRankQuery query) {
        return readPathCircuitBreaker.execute(
                () -> rankingQueryRepository.findUserRank(query.leaderboardId(), query.userId()),
                () -> userRankFromSnapshot(query)
        );
    }

    private TopRanksResult topRanksFromSnapshot(GetTopRanksQuery query) {
        RankingSnapshot snapshot = usableSnapshot(query.leaderboardId());
        List<TopRankItem> entries = snapshot.entries();
        long end = (long) query.offset() + query.limit();
        // 잘린 스냅샷에서 Top-N 경계를 넘는 페이지는 "목록 끝"처럼 보이므로 주지 않는다.
        if (!snapshot.complete() && end > entries.size()) {
            throw unavailable(Reason.NOT_IN_SNAPSHOT);
        }
        List<TopRankItem> page = entries.stream().skip(query.offset()).limit(query.limit()).toList();
        Long total = snapshot.complete() ? (long) entries.size() : null;
        return new TopRanksResult(query.leaderboardId(), page, total, RankingSource.SNAPSHOT, snapshot.snapshotAt());
    }

    private UserRankResult userRankFromSnapshot(GetUserRankQuery query) {
        RankingSnapshot snapshot = usableSnapshot(query.leaderboardId());
        // ponytail: Top-N(기본 1,000) 선형 탐색. N이 수만이 되면 userId 인덱스 맵을 캐시에 같이 둔다.
        Optional<TopRankItem> entry = snapshot.entries().stream()
                .filter(item -> item.userId() == query.userId())
                .findFirst();
        if (entry.isPresent()) {
            TopRankItem item = entry.get();
            return new UserRankResult(item.userId(), item.score(), item.rank(), RankingSource.SNAPSHOT, snapshot.snapshotAt());
        }
        if (snapshot.complete()) {
            // 캡처 당시 참여자 전원이 들어 있으므로 없는 유저는 미참여다(Redis 경로의 score 0 / rank null과 같다).
            return new UserRankResult(query.userId(), 0L, null, RankingSource.SNAPSHOT, snapshot.snapshotAt());
        }
        throw unavailable(Reason.NOT_IN_SNAPSHOT);
    }

    private RankingSnapshot usableSnapshot(UUID leaderboardId) {
        RankingSnapshot snapshot = latestSnapshot(leaderboardId)
                .orElseThrow(() -> unavailable(Reason.NO_SNAPSHOT));
        if (snapshot.snapshotAt().plus(maxSnapshotAge).isBefore(clock.instant())) {
            throw unavailable(Reason.SNAPSHOT_TOO_OLD);
        }
        return snapshot;
    }

    private Optional<RankingSnapshot> latestSnapshot(UUID leaderboardId) {
        Instant now = clock.instant();
        CachedSnapshot cached = snapshotCache.get(leaderboardId);
        if (cached != null && cached.loadedAt().plus(SNAPSHOT_CACHE_TTL).isAfter(now)) {
            return cached.snapshot();
        }
        Optional<RankingSnapshot> loaded = rankingSnapshotRepository.findLatest(leaderboardId);
        snapshotCache.put(leaderboardId, new CachedSnapshot(loaded, now));
        return loaded;
    }

    private RankingReadUnavailableException unavailable(Reason reason) {
        return new RankingReadUnavailableException(reason, readPathCircuitBreaker.retryAfterSeconds());
    }

    private record CachedSnapshot(Optional<RankingSnapshot> snapshot, Instant loadedAt) {
    }
}
