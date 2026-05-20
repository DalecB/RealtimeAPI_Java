package com.jake.realtimeapi.events.domain.model;

public record StreamsStatus(
        long pendingEntries,
        long consumerLag,
        String lastDeliveredId
) {
}
