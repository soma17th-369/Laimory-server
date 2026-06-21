package com.laimory.server.timeline.payload;

import com.laimory.server.timeline.ItemType;

/** payload 구체 타입 → ItemType 매핑. sealed 타입 exhaustive switch라 default가 필요 없다. */
public final class ItemTypes {

    private ItemTypes() {
    }

    public static ItemType typeOf(TimelineItemPayload payload) {
        return switch (payload) {
            case PhotoPayload ignored -> ItemType.PHOTO;
            case CalendarPayload ignored -> ItemType.CALENDAR;
            case LocationPayload ignored -> ItemType.LOCATION;
            case MovementPayload ignored -> ItemType.MOVEMENT;
        };
    }
}
