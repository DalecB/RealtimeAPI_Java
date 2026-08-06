package com.jake.realtimeapi.events.api;

import com.jake.realtimeapi.events.relay.AuditTopicStatusReader;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/kafka")
public class InternalKafkaStatusController {

    private final AuditTopicStatusReader auditTopicStatusReader;

    public InternalKafkaStatusController(AuditTopicStatusReader auditTopicStatusReader) {
        this.auditTopicStatusReader = auditTopicStatusReader;
    }

    @GetMapping("/audit-topic/status")
    public AuditTopicStatusReader.AuditTopicStatus getStatus() {
        return auditTopicStatusReader.read();
    }
}
