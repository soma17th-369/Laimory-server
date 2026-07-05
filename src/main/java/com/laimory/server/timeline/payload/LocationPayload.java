package com.laimory.server.timeline.payload;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record LocationPayload(
        String placeName,
        String areaName,
        Double latitude,
        Double longitude
) implements TimelineItemPayload {
}
