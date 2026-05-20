package com.jake.realtimeapi.snapshots.persistence.redis;

import com.jake.realtimeapi.snapshots.domain.model.SnapshotRankingRow;
import com.jake.realtimeapi.snapshots.domain.repository.SnapshotRankingQueryRepository;
import com.jake.realtimeapi.support.redis.LeaderboardRedisKeyFactory;
import com.jake.realtimeapi.support.userid.UserIdCodec;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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

        String key = LeaderboardRedisKeyFactory.rankingKey(leaderboardId);
        Set<ZSetOperations.TypedTuple<String>> tuples =
                redisTemplate.opsForZSet().reverseRangeWithScores(key, 0, limit - 1L);

        if (tuples == null || tuples.isEmpty()) {
            // Empty Guard 판단은 상위(application/worker)에서 하므로 여기서는 빈 리스트만 반환한다.
            return List.of();
        }

        List<SnapshotRankingRow> rows = new ArrayList<>(tuples.size());
        long previousScore = Long.MIN_VALUE;
        int currentRank = 0;
        int position = 0;

        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            if (tuple == null || tuple.getValue() == null || tuple.getScore() == null) {
                throw new IllegalStateException("redis ranking tuple must have value and score");
            }

            position++;
            long userId = UserIdCodec.parse(tuple.getValue());
            long score = toLongScore(tuple.getScore());

            // competition ranking: 점수가 바뀌는 지점에서만 rank를 현재 position으로 갱신한다.
            // 예) 1000, 800, 800, 600 -> 1, 2, 2, 4
            if (position == 1 || score != previousScore) {
                currentRank = position;
            }

            rows.add(new SnapshotRankingRow(userId, currentRank, score));
            previousScore = score;
        }

        return rows;
    }

    private long toLongScore(double score) {
        long converted = (long) score;
        if (Double.compare(score, converted) != 0) {
            // PRD: snapshot score는 integer only.
            throw new IllegalStateException("score must be an integer, but was: " + score);
        }
        return converted;
    }
}
