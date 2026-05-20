package com.jake.realtimeapi.snapshots.persistence.redis;

import com.jake.realtimeapi.snapshots.domain.model.SnapshotRankingRow;
import com.jake.realtimeapi.snapshots.domain.repository.SnapshotRankingRestoreRepository;
import com.jake.realtimeapi.support.redis.LeaderboardRedisKeyFactory;
import com.jake.realtimeapi.support.userid.UserIdCodec;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Repository
public class RedisSnapshotRankingRestoreRepository implements SnapshotRankingRestoreRepository {

    private final StringRedisTemplate redisTemplate;

    public RedisSnapshotRankingRestoreRepository(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public long countRanks(UUID leaderboardId) {
        String key = LeaderboardRedisKeyFactory.rankingKey(leaderboardId);
        Long size = redisTemplate.opsForZSet().zCard(key);
        return size == null ? 0L : size;
    }

    @Override
    public void replaceTopRanks(UUID leaderboardId, List<SnapshotRankingRow> rows) {
        if (leaderboardId == null) {
            throw new IllegalArgumentException("leaderboardId is required");
        }
        if (rows == null || rows.isEmpty()) {
            throw new IllegalArgumentException("rows must not be empty");
        }

        String key = LeaderboardRedisKeyFactory.rankingKey(leaderboardId);
        Set<ZSetOperations.TypedTuple<String>> tuples = new LinkedHashSet<>(rows.size());
        for (SnapshotRankingRow row : rows) {
            tuples.add(new DefaultTypedTuple<>(UserIdCodec.format(row.userId()), (double) row.score()));
        }

        // recovery는 snapshot이 source of record인 상황이므로 기존 Hot Path 값을 지우고 batch ZADD로 재구성한다.
        redisTemplate.delete(key);
        redisTemplate.opsForZSet().add(key, tuples);
    }
}
