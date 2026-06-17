package com.laimory.server.timeline.payload;

import com.laimory.server.timeline.ItemType;

public record MovementPayload(
        String fromPlace,
        String toPlace,
        String transportMode,
        String lineName
) implements TimelineItemPayload {

    @Override
    public ItemType itemType() {
        return ItemType.MOVEMENT;
    }
}
