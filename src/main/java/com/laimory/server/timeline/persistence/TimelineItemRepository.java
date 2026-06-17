package com.laimory.server.timeline.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TimelineItemRepository extends JpaRepository<TimelineItem, Long> {

    List<TimelineItem> findByTimelineCardId(Long timelineCardId);
}
