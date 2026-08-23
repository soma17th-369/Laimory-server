package com.laimory.server.timeline.repository;

import com.laimory.server.timeline.entity.TimelineEvent;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TimelineEventRepository extends JpaRepository<TimelineEvent, Long> {

    // 표시 순서 고정(DB 반환순 의존 제거): start_at, timeline_event_id 오름차순
    List<TimelineEvent> findByDailyRecordIdOrderByStartAtAscTimelineEventIdAsc(Long dailyRecordId);

    List<TimelineEvent> findByDailyRecordIdInOrderByDailyRecordIdAscStartAtAscTimelineEventIdAsc(
            Collection<Long> dailyRecordIds);
}
