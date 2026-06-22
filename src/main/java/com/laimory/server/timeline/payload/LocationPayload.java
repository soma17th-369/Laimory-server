package com.laimory.server.timeline.payload;

public record LocationPayload(
        String placeName,
        String areaName,
        Double latitude,
        Double longitude
) implements TimelineItemPayload {
}
