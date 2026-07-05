package com.laimory.server.timeline.payload;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CalendarPayload(
        String title,
        String calendarName,
        String locationText,
        String description,
        Boolean allDay
) implements TimelineItemPayload {
}
