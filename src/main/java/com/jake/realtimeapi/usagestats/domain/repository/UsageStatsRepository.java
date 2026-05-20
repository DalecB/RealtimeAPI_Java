package com.jake.realtimeapi.usagestats.domain.repository;

import com.jake.realtimeapi.usagestats.domain.model.UsageStatsDelta;

public interface UsageStatsRepository {

    void increment(UsageStatsDelta delta);
}
