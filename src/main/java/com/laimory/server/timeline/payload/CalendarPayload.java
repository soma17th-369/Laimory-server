package com.laimory.server.timeline.payload;

import com.laimory.server.timeline.ItemType;

public record CalendarPayload(
        String title,
        String calendarName,
        String locationText,
        Integer attendeesCount
) implements TimelineItemPayload {

    @Override
    public ItemType itemType() {
        return ItemType.CALENDAR;
    }
}
