package com.laimory.server.timeline.service;

import com.laimory.server.timeline.dto.TimelineEventResponse;
import com.laimory.server.timeline.dto.TimelineItemResponse;
import com.laimory.server.timeline.entity.TimelineEvent;
import com.laimory.server.timeline.entity.TimelineItem;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 단일 TimelineEvent를 junction 경유 Item 전체와 함께 API 응답으로 조립한다. */
@Service
@RequiredArgsConstructor
public class TimelineEventResponseAssembler {

    private final TimelineEventItemService timelineEventItemService;
    private final TimelineItemService timelineItemService;

    /** Item 표시 순서는 startAt null-first, startAt, timelineItemId 오름차순으로 고정한다. */
    public TimelineEventResponse toResponse(TimelineEvent event) {
        List<Long> itemIds = timelineEventItemService.findByTimelineEventId(event.getTimelineEventId()).stream()
                .map(link -> link.getTimelineItemId())
                .toList();
        List<TimelineItemResponse> items = timelineItemService.findByIds(itemIds).stream()
                .sorted(Comparator.comparing(TimelineItem::getStartAt,
                                Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(TimelineItem::getTimelineItemId))
                .map(TimelineItemResponse::from)
                .toList();
        return TimelineEventResponse.from(event, items);
    }
}
