package com.jake.realtimeapi.events.domain.repository;

import java.util.UUID;

/**
 * 리더보드 랭킹의 warm 키. 키가 없으면 Redis가 랭킹을 잃은 것(cold)으로 본다.
 * 쓰기는 Lua 안에서 같은 키를 확인한다(process_event.lua).
 */
public interface RankingWarmthRepository {

    boolean isWarm(UUID leaderboardId);

    void markWarm(UUID leaderboardId);
}
