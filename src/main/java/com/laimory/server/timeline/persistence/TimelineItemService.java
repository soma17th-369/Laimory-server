package com.laimory.server.timeline.persistence;

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

    public List<TimelineItem> findByTimelineCardId(Long timelineCardId) {
        return timelineItemRepository.findByTimelineCardId(timelineCardId);
    }
}
