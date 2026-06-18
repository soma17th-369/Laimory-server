package com.laimory.server.timeline.payload;

import com.laimory.server.timeline.ItemType;

public record PhotoPayload(
        String photoUri,
        Double latitude,
        Double longitude
) implements TimelineItemPayload {

    @Override
    public ItemType itemType() {
        return ItemType.PHOTO;
    }
}
