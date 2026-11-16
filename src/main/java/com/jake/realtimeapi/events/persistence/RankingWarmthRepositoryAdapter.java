package com.jake.realtimeapi.events.persistence;

import com.jake.realtimeapi.events.domain.repository.RankingWarmthRepository;
import com.jake.realtimeapi.events.persistence.redis.LeaderboardRedisKeyFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public class RankingWarmthRepositoryAdapter implements RankingWarmthRepository {

    private final StringRedisTemplate redisTemplate;

    public RankingWarmthRepositoryAdapter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean isWarm(UUID leaderboardId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(LeaderboardRedisKeyFactory.warmKey(leaderboardId)));
    }

    @Override
    public void markWarm(UUID leaderboardId) {
        // TTL 없음: 데이터가 유실될 때만 사라져야 한다.
        redisTemplate.opsForValue().set(LeaderboardRedisKeyFactory.warmKey(leaderboardId), "1");
    }
}
