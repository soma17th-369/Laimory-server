package com.laimory.server.timeline.repository;

import com.laimory.server.timeline.entity.TimelineItem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TimelineItemRepository extends JpaRepository<TimelineItem, Long> {

    // 표시 순서 고정(DB 반환순 의존 제거): start_at NULLS-LAST(시간 미상 아이템은 맨 뒤), 그다음 timeline_item_id 오름차순.
    // MySQL 기본 ASC는 NULL-first라 derived query로는 안 됨 → CASE 식으로 NULL을 뒤로 보낸다.
    @Query("SELECT i FROM TimelineItem i WHERE i.timelineEventId = :timelineEventId "
            + "ORDER BY CASE WHEN i.startAt IS NULL THEN 1 ELSE 0 END, i.startAt, i.timelineItemId")
    List<TimelineItem> findByTimelineEventIdOrderByStartAtNullsLast(@Param("timelineEventId") Long timelineEventId);
}
