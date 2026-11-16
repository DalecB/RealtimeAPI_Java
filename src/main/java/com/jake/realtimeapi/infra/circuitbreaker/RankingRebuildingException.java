package com.jake.realtimeapi.infra.circuitbreaker;

/** Redis가 랭킹 데이터를 잃어 스냅샷에서 다시 만드는 중이라 쓰기를 받을 수 없다. */
public class RankingRebuildingException extends RuntimeException {

    private final long retryAfterSeconds;

    public RankingRebuildingException(long retryAfterSeconds) {
        super("Ranking is being rebuilt from the latest snapshot");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
