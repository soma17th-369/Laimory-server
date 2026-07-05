package com.laimory.server.timeline.payload;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record MovementPayload(
        String fromPlace,
        String toPlace,
        String transportMode,
        String lineName
) implements TimelineItemPayload {
}
