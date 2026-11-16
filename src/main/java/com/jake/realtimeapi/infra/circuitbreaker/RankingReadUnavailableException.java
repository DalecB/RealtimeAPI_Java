package com.jake.realtimeapi.infra.circuitbreaker;

/**
 * Redis에서 못 읽고 스냅샷으로도 정확히 답할 수 없을 때 던진다. 틀린 순위를 주느니 503으로 재시도를 유도한다.
 */
public class RankingReadUnavailableException extends RuntimeException {

    public enum Reason {
        // 리더보드의 스냅샷이 한 번도 없다
        NO_SNAPSHOT,
        // 스냅샷이 허용 나이(10분)를 넘었다
        SNAPSHOT_TOO_OLD,
        // 요청한 유저가 스냅샷 Top-N 밖이라 순위를 알 수 없다
        NOT_IN_SNAPSHOT
    }

    private final Reason reason;
    private final long retryAfterSeconds;

    public RankingReadUnavailableException(Reason reason, long retryAfterSeconds) {
        super("Ranking is temporarily unavailable: " + reason);
        this.reason = reason;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public Reason getReason() {
        return reason;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
