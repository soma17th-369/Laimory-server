package com.laimory.server.timeline.repository;

import com.laimory.server.timeline.entity.TimelineEventItem;
import com.laimory.server.timeline.entity.TimelineEventItemId;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * timeline_event_items(junction) 레포. 조회는 event/item ID 축 양방향이다. 삭제는 root(Event/Item) 행
 * 삭제의 DB FK cascade가 기본이고, Event에서 PHOTO Item 연결 해제만 잠금 조회로 얻은 행을 명시적으로 지운다.
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
     * 연결 해제 트랜잭션의 current-read 잠금 조회 — Item 행 잠금 아래에서 target junction 존재와
     * 잔여 association 판정의 단일 권위로 쓴다(스냅숏 일반 읽기는 동시 커밋된 해제를 못 봐 오판).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from TimelineEventItem l where l.id.timelineItemId = :itemId")
    List<TimelineEventItem> findByTimelineItemIdForUpdate(@Param("itemId") Long timelineItemId);
}
