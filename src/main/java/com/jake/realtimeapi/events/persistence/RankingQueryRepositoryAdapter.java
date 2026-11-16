package com.jake.realtimeapi.events.persistence;

import com.jake.realtimeapi.events.domain.model.TopRankItem;
import com.jake.realtimeapi.events.domain.model.UserRankResult;
import com.jake.realtimeapi.events.domain.repository.RankingQueryRepository;
import com.jake.realtimeapi.events.persistence.redis.LeaderboardRedisKeyFactory;
import com.jake.realtimeapi.events.persistence.redis.RankingPageReader;
import com.jake.realtimeapi.support.userid.UserIdCodec;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public class RankingQueryRepositoryAdapter implements RankingQueryRepository {

    private final StringRedisTemplate redisTemplate;

    public RankingQueryRepositoryAdapter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public List<TopRankItem> findTopByLeaderboardId(UUID leaderboardId, int offset, int limit) {
        return RankingPageReader.read(redisTemplate, LeaderboardRedisKeyFactory.rankingKey(leaderboardId), offset, limit).stream()
                .map(entry -> new TopRankItem(entry.rank(), entry.userId(), entry.score()))
                .toList();
    }

    @Override
    public UserRankResult findUserRank(UUID leaderboardId, long userId) {
        String key = LeaderboardRedisKeyFactory.rankingKey(leaderboardId);
        Double score = redisTemplate.opsForZSet().score(key, UserIdCodec.format(userId));
        if (score == null) {
            return new UserRankResult(userId, 0L, null);
        }

        long rank = computeCompetitionRank(key, score);
        return new UserRankResult(userId, toLongScore(score), Math.toIntExact(rank));
    }

    @Override
    public long countParticipants(UUID leaderboardId) {
        String key = LeaderboardRedisKeyFactory.rankingKey(leaderboardId);
        Long total = redisTemplate.opsForZSet().zCard(key);
        return total == null ? 0L : total;
    }

    private long computeCompetitionRank(String key, double score) {
        Long greaterOrEqual = redisTemplate.opsForZSet().count(key, score, Double.POSITIVE_INFINITY);
        Long equal = redisTemplate.opsForZSet().count(key, score, score);

        long ge = greaterOrEqual == null ? 0L : greaterOrEqual;
        long eq = equal == null ? 0L : equal;
        return (ge - eq) + 1L;
    }

    private long toLongScore(double score) {
        long converted = (long) score;
        if (Double.compare(score, converted) != 0) {
            throw new IllegalStateException("score must be an integer, but was: " + score);
        }
        return converted;
    }

}
