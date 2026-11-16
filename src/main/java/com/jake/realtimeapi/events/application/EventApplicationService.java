package com.jake.realtimeapi.events.application;

import com.jake.realtimeapi.events.application.command.ProcessEventCommand;
import com.jake.realtimeapi.events.application.usecase.GetStreamsStatusUseCase;
import com.jake.realtimeapi.events.application.usecase.ProcessEventUseCase;
import com.jake.realtimeapi.events.domain.model.*;
import com.jake.realtimeapi.events.domain.policy.DeltaScorePolicy;
import com.jake.realtimeapi.events.domain.repository.AuditStreamStatusRepository;
import com.jake.realtimeapi.events.domain.repository.EventCommandRepository;
import com.jake.realtimeapi.leaderboards.domain.repository.LeaderboardRepository;
import org.springframework.stereotype.Service;


@Service
public class EventApplicationService implements ProcessEventUseCase, GetStreamsStatusUseCase {

    private final EventCommandRepository eventCommandRepository;
    private final DeltaScorePolicy deltaScorePolicy;
    private final AuditStreamStatusRepository auditStreamStatusRepository;
    private final LeaderboardRepository leaderboardRepository;

    public EventApplicationService(
            EventCommandRepository eventCommandRepository,
            DeltaScorePolicy deltaScorePolicy,
            AuditStreamStatusRepository auditStreamStatusRepository,
            LeaderboardRepository leaderboardRepository
    ) {
        this.eventCommandRepository = eventCommandRepository;
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
    public StreamsStatus getStatus() {
        long pendingEntries = 0L;
        long streamLength = 0L;
        long consumerGroupLag = 0L;

        for (var leaderboardId : leaderboardRepository.findAllIds()) {
            StreamsStatus status = auditStreamStatusRepository.getStatus(leaderboardId);
            pendingEntries += status.pendingEntries();
            streamLength += status.streamLength();
            consumerGroupLag += status.consumerGroupLag();
        }

        return new StreamsStatus(pendingEntries, streamLength, consumerGroupLag);
    }
}
