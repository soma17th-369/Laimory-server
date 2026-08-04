package com.laimory.server.timeline.repository;

import com.laimory.server.timeline.entity.TimelineEventItem;
import com.laimory.server.timeline.entity.TimelineEventItemId;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * timeline_event_items(junction) 레포. 조회는 event/item ID 축 양방향이다. 삭제는 root(Event/Item) 행
 * 삭제의 DB FK cascade가 기본이고, Event에서 PHOTO Item 연결 해제만 직접 DELETE로 행을 명시적으로 지운다.
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

    /**
     * 연결 해제의 단건 junction 직접 DELETE. 영속성 컨텍스트를 거치지 않고 영향 행 수를 반환하므로,
     * 이미 지워진 행(같은 junction 동시 해제의 후발 요청)은 예외 대신 0으로 알려진다.
     */
    @Modifying
    @Query("delete from TimelineEventItem l "
            + "where l.id.timelineEventId = :eventId and l.id.timelineItemId = :itemId")
    int deleteByEventIdAndItemId(@Param("eventId") Long timelineEventId, @Param("itemId") Long timelineItemId);
}
