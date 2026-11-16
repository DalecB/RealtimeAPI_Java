package com.jake.realtimeapi.support.redis;

import com.jake.realtimeapi.support.userid.UserIdCodec;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * 랭킹 ZSET의 한 페이지를 PRD `5. Ranking Policy` 순서로 읽는다: 점수 내림차순, 동점은 userId 숫자 오름차순, competition rank(1,2,2,4).
 * 조회 API와 스냅샷 캡처가 같은 순서를 쓰도록 둘 다 이 클래스를 거친다.
 */
public final class RankingPageReader {

    public record Entry(int rank, long userId, long score) {
    }

    private static final Comparator<Entry> ORDER =
            Comparator.comparingLong(Entry::score).reversed().thenComparingLong(Entry::userId);

    private RankingPageReader() {
    }

    public static List<Entry> read(StringRedisTemplate redisTemplate, String key, long offset, int limit) {
        ZSetOperations<String, String> zSet = redisTemplate.opsForZSet();
        Set<ZSetOperations.TypedTuple<String>> page = zSet.reverseRangeWithScores(key, offset, offset + limit - 1);
        if (page == null || page.isEmpty()) {
            return List.of();
        }

        // ZREVRANGE는 동점을 member 문자열 역순으로 준다("9"가 "10"보다 앞). 페이지에 걸친 점수 구간을 동점 그룹째 읽어 다시 정렬한다.
        // 페이지 안의 멤버는 점수 순서만으로 [low, high] 구간에 있고, 이 구간의 멤버 전체는 전역 위치 above부터 연속해서 놓인다.
        // ponytail: 페이지 끝에 걸친 동점 그룹 크기만큼 읽는다. 수만 명이 같은 점수면 동점 기준을 점수에 접어 넣는 방식으로 바꾼다.
        double high = page.iterator().next().getScore();
        double low = high;
        for (ZSetOperations.TypedTuple<String> tuple : page) {
            low = tuple.getScore();
        }
        Set<ZSetOperations.TypedTuple<String>> band = zSet.rangeByScoreWithScores(key, low, high);
        Long aboveCount = zSet.count(key, Math.nextUp(high), Double.POSITIVE_INFINITY);
        long above = aboveCount == null ? 0L : aboveCount;

        List<Entry> sorted = band.stream()
                .map(tuple -> new Entry(0, UserIdCodec.parse(tuple.getValue()), toLongScore(tuple.getScore())))
                .sorted(ORDER)
                .toList();

        List<Entry> result = new ArrayList<>(limit);
        long from = offset - above;
        int rank = 0;
        Long previousScore = null;
        for (int i = 0; i < sorted.size() && result.size() < limit; i++) {
            Entry entry = sorted.get(i);
            if (previousScore == null || entry.score() != previousScore) {
                rank = Math.toIntExact(above + i + 1);
            }
            previousScore = entry.score();
            if (i >= from) {
                result.add(new Entry(rank, entry.userId(), entry.score()));
            }
        }
        return result;
    }

    private static long toLongScore(Double score) {
        if (score == null) {
            throw new IllegalStateException("redis ranking tuple must have a score");
        }
        long converted = score.longValue();
        if (Double.compare(score, converted) != 0) {
            throw new IllegalStateException("score must be an integer, but was: " + score);
        }
        return converted;
    }
}
