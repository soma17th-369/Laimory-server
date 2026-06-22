package com.laimory.server.timeline.payload;

public record PhotoPayload(
        String photoUri,
        Double latitude,
        Double longitude
) implements TimelineItemPayload {
}
