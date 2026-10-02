package com.laimory.server.timeline.repository;

import com.laimory.server.timeline.entity.TimelineEvent;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TimelineEventRepository extends JpaRepository<TimelineEvent, Long> {

    /** PK 단건 조회 — 상속 {@code findById}와 달리 인터페이스 선언이라 transaction 없이 실행된다(#499). */
    Optional<TimelineEvent> findByTimelineEventId(Long timelineEventId);

    // 표시 순서 고정(DB 반환순 의존 제거): start_at, timeline_event_id 오름차순
    List<TimelineEvent> findByDailyRecordIdOrderByStartAtAscTimelineEventIdAsc(Long dailyRecordId);

    List<TimelineEvent> findByDailyRecordIdInOrderByDailyRecordIdAscStartAtAscTimelineEventIdAsc(
            Collection<Long> dailyRecordIds);
}
