package com.jake.realtimeapi.events.application;

import com.jake.realtimeapi.events.application.command.ProcessEventCommand;
import com.jake.realtimeapi.events.application.query.GetTopRanksQuery;
import com.jake.realtimeapi.events.application.query.GetUserRankQuery;
import com.jake.realtimeapi.events.application.usecase.GetTopRanksUseCase;
import com.jake.realtimeapi.events.application.usecase.GetStreamsStatusUseCase;
import com.jake.realtimeapi.events.application.usecase.GetUserRankUseCase;
import com.jake.realtimeapi.events.application.usecase.ProcessEventUseCase;
import com.jake.realtimeapi.events.domain.model.*;
import com.jake.realtimeapi.events.domain.policy.DeltaScorePolicy;
import com.jake.realtimeapi.events.domain.repository.AuditStreamStatusRepository;
import com.jake.realtimeapi.events.domain.repository.EventCommandRepository;
import com.jake.realtimeapi.events.domain.repository.RankingQueryRepository;
import com.jake.realtimeapi.leaderboards.domain.repository.LeaderboardRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class EventApplicationService implements GetTopRanksUseCase, GetUserRankUseCase, ProcessEventUseCase, GetStreamsStatusUseCase {

    private final EventCommandRepository eventCommandRepository;
    private final RankingQueryRepository rankingQueryRepository;
    private final DeltaScorePolicy deltaScorePolicy;
    private final AuditStreamStatusRepository auditStreamStatusRepository;
    private final LeaderboardRepository leaderboardRepository;

    public EventApplicationService(
            EventCommandRepository eventCommandRepository,
            RankingQueryRepository rankingQueryRepository,
            DeltaScorePolicy deltaScorePolicy,
            AuditStreamStatusRepository auditStreamStatusRepository,
            LeaderboardRepository leaderboardRepository
    ) {
        this.eventCommandRepository = eventCommandRepository;
        this.rankingQueryRepository = rankingQueryRepository;
        this.deltaScorePolicy = deltaScorePolicy;
        this.auditStreamStatusRepository = auditStreamStatusRepository;
        this.leaderboardRepository = leaderboardRepository;
    }

    @Override
    public ProcessEventResult process(ProcessEventCommand command) {
        deltaScorePolicy.validate(command.deltaScore());

        EventPayload payload = new EventPayload(
                command.leaderboardId(),
                command.userId(),
                command.deltaScore(),
                command.idempotencyKey()
        );

        return eventCommandRepository.process(payload, command.apiKeyId());
    }

    @Override
    public UserRankResult getUserRank(GetUserRankQuery query) {
        return rankingQueryRepository.findUserRank(query.leaderboardId(), query.userId());
    }

    @Override
    public TopRanksResult getTopRanks(GetTopRanksQuery query) {
        List<TopRankItem> items = rankingQueryRepository.findTopByLeaderboardId(
                query.leaderboardId(), query.offset(), query.limit());
        long total = rankingQueryRepository.countParticipants(query.leaderboardId());
        return new TopRanksResult(query.leaderboardId(), items, total);
    }

    @Override
    public StreamsStatus getStatus() {
        long pendingEntries = 0L;
        long streamLength = 0L;

        for (var leaderboardId : leaderboardRepository.findAllIds()) {
            StreamsStatus status = auditStreamStatusRepository.getStatus(leaderboardId);
            pendingEntries += status.pendingEntries();
            streamLength += status.streamLength();
        }

        return new StreamsStatus(pendingEntries, streamLength);
    }
}
