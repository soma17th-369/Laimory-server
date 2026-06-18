package com.laimory.server.timeline.payload;

import com.laimory.server.timeline.ItemType;

public record LocationPayload(
        String placeName,
        String areaName,
        Double latitude,
        Double longitude
) implements TimelineItemPayload {

    @Override
    public ItemType itemType() {
        return ItemType.LOCATION;
    }
}
