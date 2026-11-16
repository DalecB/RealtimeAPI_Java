package com.jake.realtimeapi.events.persistence;

import com.jake.realtimeapi.events.domain.model.TopRankItem;
import com.jake.realtimeapi.events.domain.model.UserRankResult;
import com.jake.realtimeapi.events.persistence.redis.LeaderboardRedisKeyFactory;
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
    void findUserRank_returnsNullRankAndZeroScoreWhenUserDoesNotExist() {
        UUID leaderboardId = UUID.randomUUID();
        String key = LeaderboardRedisKeyFactory.rankingKey(leaderboardId);

        when(zSetOperations.score(key, UserIdCodec.format(999L))).thenReturn(null);

        UserRankResult result = repository.findUserRank(leaderboardId, 999L);

        assertEquals(999L, result.userId());
        assertEquals(0L, result.score());
        assertNull(result.rank());
    }

    @Test
    void findUserRank_returnsCompetitionRankForExistingUser() {
        UUID leaderboardId = UUID.randomUUID();
        String key = LeaderboardRedisKeyFactory.rankingKey(leaderboardId);

        when(zSetOperations.score(key, UserIdCodec.format(20L))).thenReturn(800.0);
        when(zSetOperations.count(key, 800.0, Double.POSITIVE_INFINITY)).thenReturn(3L);
        when(zSetOperations.count(key, 800.0, 800.0)).thenReturn(2L);

        UserRankResult result = repository.findUserRank(leaderboardId, 20L);

        assertEquals(new UserRankResult(20L, 800L, 2), result);
    }
}
