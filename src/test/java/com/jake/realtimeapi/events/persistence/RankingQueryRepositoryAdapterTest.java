package com.jake.realtimeapi.events.persistence;

import com.jake.realtimeapi.events.domain.model.TopRankItem;
import com.jake.realtimeapi.events.domain.model.UserRankResult;
import com.jake.realtimeapi.events.persistence.redis.EventRedisKeyFactory;
import com.jake.realtimeapi.support.userid.UserIdCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RankingQueryRepositoryAdapterTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ZSetOperations<String, String> zSetOperations;

    private RankingQueryRepositoryAdapter repository;

    @BeforeEach
    void setUp() {
        repository = new RankingQueryRepositoryAdapter(redisTemplate);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
    }

    @Test
    void findTopByLeaderboardId_returnsCompetitionRanksUsingRedisSortedSet() {
        UUID leaderboardId = UUID.randomUUID();
        String key = EventRedisKeyFactory.rankingKey(leaderboardId);

        Set<ZSetOperations.TypedTuple<String>> tuples = new LinkedHashSet<>();
        tuples.add(ZSetOperations.TypedTuple.of(UserIdCodec.format(10L), 1000.0));
        tuples.add(ZSetOperations.TypedTuple.of(UserIdCodec.format(20L), 800.0));
        tuples.add(ZSetOperations.TypedTuple.of(UserIdCodec.format(30L), 800.0));

        when(zSetOperations.reverseRangeWithScores(key, 0, 2)).thenReturn(tuples);
        when(zSetOperations.count(key, 1000.0, Double.POSITIVE_INFINITY)).thenReturn(1L);
        when(zSetOperations.count(key, 1000.0, 1000.0)).thenReturn(1L);

        List<TopRankItem> result = repository.findTopByLeaderboardId(leaderboardId, 0, 3);

        assertEquals(
                List.of(
                        new TopRankItem(1, 10L, 1000L),
                        new TopRankItem(2, 20L, 800L),
                        new TopRankItem(2, 30L, 800L)
                ),
                result
        );
        verify(zSetOperations).reverseRangeWithScores(key, 0, 2);
    }

    @Test
    void findTopByLeaderboardId_usesOffsetAndEndRangeFromLimit() {
        UUID leaderboardId = UUID.randomUUID();
        String key = EventRedisKeyFactory.rankingKey(leaderboardId);

        Set<ZSetOperations.TypedTuple<String>> tuples = new LinkedHashSet<>();
        tuples.add(ZSetOperations.TypedTuple.of(UserIdCodec.format(40L), 700.0));
        tuples.add(ZSetOperations.TypedTuple.of(UserIdCodec.format(50L), 650.0));

        when(zSetOperations.reverseRangeWithScores(key, 3, 4)).thenReturn(tuples);
        when(zSetOperations.count(key, 700.0, Double.POSITIVE_INFINITY)).thenReturn(4L);
        when(zSetOperations.count(key, 700.0, 700.0)).thenReturn(1L);

        List<TopRankItem> result = repository.findTopByLeaderboardId(leaderboardId, 3, 2);

        assertEquals(2, result.size());
        assertEquals(new TopRankItem(4, 40L, 700L), result.get(0));
        assertEquals(new TopRankItem(5, 50L, 650L), result.get(1));
        verify(zSetOperations).reverseRangeWithScores(key, 3, 4);
    }

    @Test
    void findUserRank_returnsNullRankAndZeroScoreWhenUserDoesNotExist() {
        UUID leaderboardId = UUID.randomUUID();
        String key = EventRedisKeyFactory.rankingKey(leaderboardId);

        when(zSetOperations.score(key, UserIdCodec.format(999L))).thenReturn(null);

        UserRankResult result = repository.findUserRank(leaderboardId, 999L);

        assertEquals(999L, result.userId());
        assertEquals(0L, result.score());
        assertNull(result.rank());
    }

    @Test
    void findUserRank_returnsCompetitionRankForExistingUser() {
        UUID leaderboardId = UUID.randomUUID();
        String key = EventRedisKeyFactory.rankingKey(leaderboardId);

        when(zSetOperations.score(key, UserIdCodec.format(20L))).thenReturn(800.0);
        when(zSetOperations.count(key, 800.0, Double.POSITIVE_INFINITY)).thenReturn(3L);
        when(zSetOperations.count(key, 800.0, 800.0)).thenReturn(2L);

        UserRankResult result = repository.findUserRank(leaderboardId, 20L);

        assertEquals(new UserRankResult(20L, 800L, 2), result);
    }

    @Test
    void findTopByLeaderboardId_rejectsNonIntegerScore() {
        UUID leaderboardId = UUID.randomUUID();
        String key = EventRedisKeyFactory.rankingKey(leaderboardId);

        Set<ZSetOperations.TypedTuple<String>> tuples = new LinkedHashSet<>();
        tuples.add(ZSetOperations.TypedTuple.of(UserIdCodec.format(10L), 1000.5));

        when(zSetOperations.reverseRangeWithScores(key, 0, 0)).thenReturn(tuples);
        when(zSetOperations.count(key, 1000.5, Double.POSITIVE_INFINITY)).thenReturn(1L);
        when(zSetOperations.count(key, 1000.5, 1000.5)).thenReturn(1L);

        assertThrows(
                IllegalStateException.class,
                () -> repository.findTopByLeaderboardId(leaderboardId, 0, 1)
        );
    }
}
