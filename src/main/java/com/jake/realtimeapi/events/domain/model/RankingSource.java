package com.jake.realtimeapi.events.domain.model;

/** 랭킹 응답이 어디서 왔는지. SNAPSHOT이면 asOf 시점의 값이다. */
public enum RankingSource {
    REDIS,
    SNAPSHOT
}
