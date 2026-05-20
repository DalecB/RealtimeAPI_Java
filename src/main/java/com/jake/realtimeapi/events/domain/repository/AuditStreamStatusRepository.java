package com.jake.realtimeapi.events.domain.repository;

import com.jake.realtimeapi.events.domain.model.StreamsStatus;

import java.util.UUID;

public interface AuditStreamStatusRepository {

    StreamsStatus getStatus(UUID leaderboardId);
}
