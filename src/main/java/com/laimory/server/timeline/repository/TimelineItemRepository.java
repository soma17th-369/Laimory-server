package com.laimory.server.timeline.repository;

import com.laimory.server.timeline.entity.TimelineItem;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TimelineItemRepository extends JpaRepository<TimelineItem, Long> {

    // 표시 순서 고정(DB 반환순 의존 제거): start_at, timeline_item_id 오름차순.
    // start_at은 nullable이며 정렬은 MySQL 기본(ASC NULLS-FIRST)을 그대로 사용 — null 위치는 보장하지 않는다.
    List<TimelineItem> findByTimelineEventIdOrderByStartAtAscTimelineItemIdAsc(Long timelineEventId);

    // append 시 이미 저장된 source item을 rawId로 제외하기 위한 projection 조회.
    // rawId만 select 한다(JSON payload를 든 전체 엔티티 로드 회피). 후보 rawIds로 IN을 좁혀 하루 전체 스캔을 막는다.
    @Query("select ti.rawId from TimelineItem ti where ti.timelineEventId in :eventIds and ti.rawId in :rawIds")
    List<String> findRawIdsByTimelineEventIdInAndRawIdIn(@Param("eventIds") Collection<Long> eventIds,
                                                         @Param("rawIds") Collection<String> rawIds);
}
