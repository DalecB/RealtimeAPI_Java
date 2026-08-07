package com.jake.realtimeapi.events.api;

import com.jake.realtimeapi.events.consumer.AuditEventQueryRepository;
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

    /** 루프 확인: Kafka produced 대비 PG로 실제 적재된 총 건수. */
    @GetMapping("/count")
    public AuditEventCount count() {
        return new AuditEventCount(auditEventQueryRepository.count());
    }

    /** 추이 페이지 드롭다운: 적재된 이벤트가 있는 리더보드만. */
    @GetMapping("/leaderboards")
    public List<UUID> leaderboards() {
        return auditEventQueryRepository.leaderboardsWithEvents();
    }

    /** 리더보드 점수 추이: 구간을 버킷으로 묶은 delta 합·건수. 누적은 클라이언트가 러닝 합으로 그린다. */
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
}
