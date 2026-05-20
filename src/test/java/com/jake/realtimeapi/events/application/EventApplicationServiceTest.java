package com.jake.realtimeapi.events.application;

import com.jake.realtimeapi.events.domain.model.StreamsStatus;
import com.jake.realtimeapi.events.domain.repository.AuditStreamStatusRepository;
import com.jake.realtimeapi.events.domain.repository.EventCommandRepository;
import com.jake.realtimeapi.events.domain.repository.RankingQueryRepository;
import com.jake.realtimeapi.events.domain.policy.DeltaScorePolicy;
import com.jake.realtimeapi.leaderboards.domain.repository.LeaderboardRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EventApplicationServiceTest {

    @Mock
    private EventCommandRepository eventCommandRepository;

    @Mock
    private RankingQueryRepository rankingQueryRepository;

    @Mock
    private DeltaScorePolicy deltaScorePolicy;

    @Mock
    private AuditStreamStatusRepository auditStreamStatusRepository;

    @Mock
    private LeaderboardRepository leaderboardRepository;

    @InjectMocks
    private EventApplicationService eventApplicationService;

    @Test
    void getStatus_aggregatesAllLeaderboardStreams() {
        UUID leaderboardOne = UUID.randomUUID();
        UUID leaderboardTwo = UUID.randomUUID();
        when(leaderboardRepository.findAllIds()).thenReturn(List.of(leaderboardOne, leaderboardTwo));
        when(auditStreamStatusRepository.getStatus(leaderboardOne)).thenReturn(new StreamsStatus(0, 3, "100-0"));
        when(auditStreamStatusRepository.getStatus(leaderboardTwo)).thenReturn(new StreamsStatus(0, 5, "101-0"));

        StreamsStatus result = eventApplicationService.getStatus();

        assertEquals(0L, result.pendingEntries());
        assertEquals(8L, result.consumerLag());
        assertEquals("101-0", result.lastDeliveredId());
    }
}
