package com.jake.realtimeapi.snapshots.persistence.redis;

import com.jake.realtimeapi.events.persistence.redis.LeaderboardRedisKeyFactory;
import com.jake.realtimeapi.snapshots.domain.model.SnapshotRankingRow;
import com.jake.realtimeapi.snapshots.domain.repository.SnapshotRankingQueryRepository;
import com.jake.realtimeapi.events.persistence.redis.LeaderboardRedisKeyFactory;
import com.jake.realtimeapi.events.persistence.redis.RankingPageReader;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public class RedisSnapshotRankingQueryRepository implements SnapshotRankingQueryRepository {

    private static final int MIN_LIMIT = 1;
    private static final int MAX_LIMIT = 1_000;
    private final StringRedisTemplate redisTemplate;

    public RedisSnapshotRankingQueryRepository(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public List<SnapshotRankingRow> findTopRanks(UUID leaderboardId, int limit) {
        if (leaderboardId == null) {
            throw new IllegalArgumentException("leaderboardId is required");
        }
        if (limit < MIN_LIMIT || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and 1000, but was " + limit);
        }

        // 조회 API와 같은 순서로 읽어야 Top-N 경계의 동점 그룹에서 같은 멤버가 스냅샷에 들어간다.
        // 빈 결과의 Empty Guard 판단은 상위(application/worker)에서 한다.
        return RankingPageReader.read(redisTemplate, LeaderboardRedisKeyFactory.rankingKey(leaderboardId), 0, limit).stream()
                .map(entry -> new SnapshotRankingRow(entry.userId(), entry.rank(), entry.score()))
                .toList();
    }
}
