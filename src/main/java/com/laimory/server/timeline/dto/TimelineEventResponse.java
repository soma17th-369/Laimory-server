package com.laimory.server.timeline.dto;

import com.laimory.server.timeline.entity.TimelineEvent;
import java.time.LocalDateTime;
import java.util.List;

/** 타임라인 이벤트 응답 DTO(하위 아이템 응답 목록 포함). */
public record TimelineEventResponse(
        Long timelineEventId,
        LocalDateTime startAt,
        LocalDateTime endAt,
        String title,
        String subtitle,
        String memo,
        List<TimelineItemResponse> items
) {

    public static TimelineEventResponse from(TimelineEvent event, List<TimelineItemResponse> items) {
        return new TimelineEventResponse(
                event.getTimelineEventId(),
                event.getStartAt(),
                event.getEndAt(),
                event.getTitle(),
                event.getSubtitle(),
                event.getMemo(),
                items
        );
    }
}
