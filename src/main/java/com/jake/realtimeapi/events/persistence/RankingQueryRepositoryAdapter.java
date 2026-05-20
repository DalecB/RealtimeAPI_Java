package com.jake.realtimeapi.events.persistence;

import com.jake.realtimeapi.events.domain.model.TopRankItem;
import com.jake.realtimeapi.events.domain.model.UserRankResult;
import com.jake.realtimeapi.events.domain.repository.RankingQueryRepository;
import com.jake.realtimeapi.events.persistence.redis.EventRedisKeyFactory;
import com.jake.realtimeapi.support.userid.UserIdCodec;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Repository
public class RankingQueryRepositoryAdapter implements RankingQueryRepository {

    private final StringRedisTemplate redisTemplate;

    public RankingQueryRepositoryAdapter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public List<TopRankItem> findTopByLeaderboardId(UUID leaderboardId, int offset, int limit) {
        String key = EventRedisKeyFactory.rankingKey(leaderboardId);
        long end = (long) offset + limit - 1;

        Set<ZSetOperations.TypedTuple<String>> tuples = redisTemplate.opsForZSet().reverseRangeWithScores(key, offset, end);
        if (tuples == null || tuples.isEmpty()) {
            return List.of();
        }

        List<TopRankItem> items = new ArrayList<>(tuples.size());
        Double previousScore = null;
        long currentRank = 0L;
        int indexInPage = 0;

        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            if (tuple == null || tuple.getValue() == null || tuple.getScore() == null) {
                continue;
            }

            long userId = UserIdCodec.parse(tuple.getValue());
            double score = tuple.getScore();

            if (indexInPage == 0) {
                currentRank = computeCompetitionRank(key, score);
            } else if (!scoreEquals(previousScore, score)) {
                currentRank = (long) offset + indexInPage + 1;
            }

            items.add(new TopRankItem(Math.toIntExact(currentRank), userId, toLongScore(score)));
            previousScore = score;
            indexInPage++;
        }

        return items;
    }

    @Override
    public UserRankResult findUserRank(UUID leaderboardId, long userId) {
        String key = EventRedisKeyFactory.rankingKey(leaderboardId);
        Double score = redisTemplate.opsForZSet().score(key, UserIdCodec.format(userId));
        if (score == null) {
            return new UserRankResult(userId, 0L, null);
        }

        long rank = computeCompetitionRank(key, score);
        return new UserRankResult(userId, toLongScore(score), Math.toIntExact(rank));
    }

    @Override
    public long countParticipants(UUID leaderboardId) {
        String key = EventRedisKeyFactory.rankingKey(leaderboardId);
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

    private boolean scoreEquals(Double left, double right) {
        return left != null && Double.compare(left, right) == 0;
    }
}
