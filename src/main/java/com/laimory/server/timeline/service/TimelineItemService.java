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

    /** 해당 카드의 아이템을 start_at, id 오름차순으로 반환(표시 순서 고정). */
    public List<TimelineItem> findByTimelineCardId(Long timelineCardId) {
        return timelineItemRepository.findByTimelineCardIdOrderByStartAtAscIdAsc(timelineCardId);
    }
}
