package com.jake.realtimeapi.events.domain.model;

import java.time.Instant;
import java.util.List;

/**
 * 읽기 fallback용 최신 스냅샷. entries는 rank 오름차순이다.
 * entries가 topN보다 적으면 캡처 당시 참여자 전원이 들어 있다는 뜻이라(complete) 없는 유저 = 미참여로 답할 수 있다.
 */
public record RankingSnapshot(
        Instant snapshotAt,
        int topN,
        List<TopRankItem> entries
) {
    public RankingSnapshot {
        if (snapshotAt == null) {
            throw new IllegalArgumentException("snapshotAt is required");
        }
        entries = List.copyOf(entries);
    }

    public boolean complete() {
        return entries.size() < topN;
    }
}
