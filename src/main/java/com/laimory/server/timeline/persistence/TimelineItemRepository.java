package com.laimory.server.timeline.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TimelineItemRepository extends JpaRepository<TimelineItem, Long> {

    // 표시 순서 고정(DB 반환순 의존 제거): start_at, id 오름차순
    List<TimelineItem> findByTimelineCardIdOrderByStartAtAscIdAsc(Long timelineCardId);
}
