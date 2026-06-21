package com.laimory.server.timeline.repository;

import com.laimory.server.timeline.entity.TimelineItem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TimelineItemRepository extends JpaRepository<TimelineItem, Long> {

    // 표시 순서 고정(DB 반환순 의존 제거): start_at, timeline_item_id 오름차순
    List<TimelineItem> findByTimelineEventIdOrderByStartAtAscTimelineItemIdAsc(Long timelineEventId);
}
