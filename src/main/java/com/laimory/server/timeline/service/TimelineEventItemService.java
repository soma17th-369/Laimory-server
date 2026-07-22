package com.laimory.server.timeline.service;

import com.laimory.server.timeline.entity.TimelineEventItem;
import com.laimory.server.timeline.repository.TimelineEventItemRepository;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** timeline_event_items(junction) leaf 서비스. 자신과 1:1인 TimelineEventItemRepository에만 접근한다. */
@Service
@RequiredArgsConstructor
public class TimelineEventItemService {

    private final TimelineEventItemRepository timelineEventItemRepository;

    public List<TimelineEventItem> saveAll(List<TimelineEventItem> links) {
        return timelineEventItemRepository.saveAll(links);
    }

    public List<TimelineEventItem> findByTimelineEventId(Long timelineEventId) {
        return timelineEventItemRepository.findByTimelineEventId(timelineEventId);
    }

    /** 빈 입력이면 빈 목록(불필요한 빈 IN 쿼리 회피) — 이하 IN 조회 공통. */
    public List<TimelineEventItem> findByTimelineEventIds(Collection<Long> timelineEventIds) {
        if (timelineEventIds.isEmpty()) {
            return List.of();
        }
        return timelineEventItemRepository.findByTimelineEventIdIn(timelineEventIds);
    }

    /** 후보 Item들의 현재 association 전체 — 삭제 흐름의 exclusive/shared 판정과 orphan 검출에 쓴다. */
    public List<TimelineEventItem> findByTimelineItemIds(Collection<Long> timelineItemIds) {
        if (timelineItemIds.isEmpty()) {
            return List.of();
        }
        return timelineEventItemRepository.findByTimelineItemIdIn(timelineItemIds);
    }
}
