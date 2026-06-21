package com.laimory.server.timeline.payload;

public record MovementPayload(
        String fromPlace,
        String toPlace,
        String transportMode,
        String lineName
) implements TimelineItemPayload {
}
