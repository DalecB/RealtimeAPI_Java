package com.jake.realtimeapi.events.api;

import com.jake.realtimeapi.events.persistence.AuditEventQueryRepository;
import com.jake.realtimeapi.support.userid.UserIdCodec;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/internal/audit-events")
public class InternalAuditEventsController {

    private final AuditEventQueryRepository auditEventQueryRepository;

    public InternalAuditEventsController(AuditEventQueryRepository auditEventQueryRepository) {
        this.auditEventQueryRepository = auditEventQueryRepository;
    }

    @GetMapping("/count")
    public AuditEventCount count() {
        return new AuditEventCount(auditEventQueryRepository.count());
    }

    @GetMapping("/leaderboards")
    public List<UUID> leaderboards() {
        return auditEventQueryRepository.leaderboardsWithEvents();
    }

    @GetMapping("/recent")
    public List<RecentAuditEventResponse> recent(
            @RequestParam UUID leaderboardId,
            @RequestParam(defaultValue = "20") int limit
    ) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
        return auditEventQueryRepository.recent(leaderboardId, limit).stream()
                .map(event -> new RecentAuditEventResponse(
                        event.eventTime(),
                        event.eventId(),
                        event.eventType(),
                        UserIdCodec.format(event.userId()),
                        event.delta(),
                        Long.toString(event.apiKeyId()),
                        event.idempotencyKey()
                ))
                .toList();
    }

    @GetMapping("/trend")
    public List<AuditEventQueryRepository.TrendBucket> trend(
            @RequestParam UUID leaderboardId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "5m") String bucket
    ) {
        return auditEventQueryRepository.trend(leaderboardId, from, to, bucket);
    }

    public record AuditEventCount(long count) {
    }

    public record RecentAuditEventResponse(
            Instant eventTime,
            String eventId,
            String eventType,
            String userId,
            long delta,
            String apiKeyId,
            String idempotencyKey
    ) {
    }
}
