package com.jake.realtimeapi.events.api.dto;

public record StreamsStatusResponse(
        long pendingEntries,
        long streamLength,
        long consumerGroupLag
) {
}
