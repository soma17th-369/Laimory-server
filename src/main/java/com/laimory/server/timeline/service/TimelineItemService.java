package com.laimory.server.timeline.service;

import com.laimory.server.timeline.entity.TimelineItem;
import com.laimory.server.timeline.repository.TimelineItemRepository;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** timeline_items leaf 서비스. 자신과 1:1인 TimelineItemRepository에만 접근한다. */
@Service
@RequiredArgsConstructor
public class TimelineItemService {

    private final TimelineItemRepository timelineItemRepository;

    public TimelineItem save(TimelineItem item) {
        return timelineItemRepository.save(item);
    }

    /** 해당 이벤트의 아이템을 표시 순서로 반환: start_at, timeline_item_id 오름차순 (null 위치 미보장). */
    public List<TimelineItem> findByTimelineEventId(Long timelineEventId) {
        return timelineItemRepository.findByTimelineEventIdOrderByStartAtAscTimelineItemIdAsc(timelineEventId);
    }

    /**
     * 주어진 이벤트들에 이미 저장된 아이템 중 rawId가 후보에 속하는 것들의 rawId 집합을 반환한다.
     * append 시 이미 타임라인에 반영된 source item을 rawId로 제외하는 데 쓴다.
     * eventIds 또는 rawIds가 비면 빈 집합을 반환한다(불필요한 빈 IN 쿼리 회피).
     */
    public Set<String> findSavedRawIds(Collection<Long> eventIds, Collection<String> rawIds) {
        if (eventIds.isEmpty() || rawIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(timelineItemRepository.findRawIdsByTimelineEventIdInAndRawIdIn(eventIds, rawIds));
    }
}
