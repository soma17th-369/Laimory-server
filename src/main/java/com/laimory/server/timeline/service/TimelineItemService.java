package com.laimory.server.timeline.service;

import com.laimory.server.timeline.entity.TimelineItem;
import com.laimory.server.timeline.repository.TimelineItemRepository;
import java.util.List;
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

    /** 해당 이벤트의 아이템을 표시 순서로 반환: start_at NULLS-LAST(시간 미상 아이템은 맨 뒤), 그다음 timeline_item_id 오름차순. */
    public List<TimelineItem> findByTimelineEventId(Long timelineEventId) {
        return timelineItemRepository.findByTimelineEventIdOrderByStartAtNullsLast(timelineEventId);
    }
}
