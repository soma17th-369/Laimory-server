package com.laimory.server.timeline.repository;

import com.laimory.server.timeline.entity.TimelineEventItem;
import com.laimory.server.timeline.entity.TimelineEventItemId;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * timeline_event_items(junction) 레포. 조회는 event/item ID 축 양방향, 삭제는 DB FK cascade가 담당한다.
 *
 * <p>키가 {@code @EmbeddedId}(중첩 경로 {@code id.timelineEventId})라 파생 쿼리명은 {@code findByIdTimelineEventId}
 * 처럼 id 중첩을 노출한다. 메서드명을 깔끔하게 유지하려고 명시적 {@code @Query}로 중첩 경로를 감춘다.
 */
public interface TimelineEventItemRepository extends JpaRepository<TimelineEventItem, TimelineEventItemId> {

    @Query("select l from TimelineEventItem l where l.id.timelineEventId = :eventId")
    List<TimelineEventItem> findByTimelineEventId(@Param("eventId") Long timelineEventId);

    @Query("select l from TimelineEventItem l where l.id.timelineEventId in :eventIds")
    List<TimelineEventItem> findByTimelineEventIdIn(@Param("eventIds") Collection<Long> timelineEventIds);

    /** 후보 Item들의 현재 association 전체 — 삭제 흐름의 exclusive/shared 판정과 orphan 검출에 쓴다. */
    @Query("select l from TimelineEventItem l where l.id.timelineItemId in :itemIds")
    List<TimelineEventItem> findByTimelineItemIdIn(@Param("itemIds") Collection<Long> timelineItemIds);
}
