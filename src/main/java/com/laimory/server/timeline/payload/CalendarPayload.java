package com.laimory.server.timeline.payload;

public record CalendarPayload(
        String title,
        String calendarName,
        String locationText
) implements TimelineItemPayload {
}
