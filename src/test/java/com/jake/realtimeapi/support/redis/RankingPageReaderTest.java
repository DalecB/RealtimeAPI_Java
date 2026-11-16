package com.jake.realtimeapi.support.redis;

import com.jake.realtimeapi.TestcontainersConfiguration;
import com.jake.realtimeapi.support.redis.RankingPageReader.Entry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * PRD `5. Ranking Policy` 순서를 실제 Redis로 검증한다. ZSET 자체는 동점을 member 문자열 순으로 두므로 mock으로는 의미가 없다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RankingPageReaderTest {

    @Autowired
    private StringRedisTemplate redisTemplate;

    private String key;

    @BeforeEach
    void freshKey() {
        key = LeaderboardRedisKeyFactory.rankingKey(UUID.randomUUID());
    }

    private void add(long userId, long score) {
        redisTemplate.opsForZSet().add(key, Long.toString(userId), score);
    }

    @Test
    void tiesSortByNumericUserIdAscending() {
        // 문자열 순서라면 "9" > "2" > "10"(역순) 또는 "10" < "2" < "9"(정순)가 된다
        add(9, 500);
        add(10, 500);
        add(2, 500);

        assertEquals(
                List.of(new Entry(1, 2, 500), new Entry(1, 9, 500), new Entry(1, 10, 500)),
                RankingPageReader.read(redisTemplate, key, 0, 10));
    }

    @Test
    void competitionRanksAndTieOrderHoldAcrossPageBoundaries() {
        add(1, 900);
        add(20, 800);
        add(3, 800);
        add(100, 800);
        add(5, 700);

        assertEquals(List.of(new Entry(1, 1, 900), new Entry(2, 3, 800)), RankingPageReader.read(redisTemplate, key, 0, 2));
        assertEquals(List.of(new Entry(2, 20, 800), new Entry(2, 100, 800)), RankingPageReader.read(redisTemplate, key, 2, 2));
        assertEquals(List.of(new Entry(5, 5, 700)), RankingPageReader.read(redisTemplate, key, 4, 2));
        assertEquals(List.of(), RankingPageReader.read(redisTemplate, key, 5, 2));
    }

    @Test
    void rejectsNonIntegerScores() {
        redisTemplate.opsForZSet().add(key, "1", 100.5);

        assertThrows(IllegalStateException.class, () -> RankingPageReader.read(redisTemplate, key, 0, 1));
    }
}
