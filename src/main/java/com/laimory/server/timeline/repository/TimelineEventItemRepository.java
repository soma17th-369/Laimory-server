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
     * 주어진 record들의 Event에 연결된 Item id(#302 계정 삭제의 owner snapshot).
     * 연관 매핑 없이 FK 값으로 join한다(저장소 방침).
     */
    @Query(value = "select distinct l.timeline_item_id from timeline_event_items l "
            + "join timeline_events e on e.timeline_event_id = l.timeline_event_id "
            + "where e.daily_record_id in (:dailyRecordIds)",
            nativeQuery = true)
    List<Long> findItemIdsByDailyRecordIdIn(@Param("dailyRecordIds") Collection<Long> dailyRecordIds);

    /**
     * 주어진 Item들을 현재 소유한 subject 전부(#302 fail-closed 검사).
     *
     * <p>정상 불변식이면 결과는 대상 subject 하나이거나(연결된 Item) 비어 있다(junction 0). 다른 subject가
     * 섞여 나오면 손상 상태이므로 조용히 지우지 않고 수동 확인으로 보낸다 — 남의 데이터를 지우는 것보다
     * 멈추는 편이 낫다.
     */
    @Query(value = "select distinct r.subject_id from timeline_event_items l "
            + "join timeline_events e on e.timeline_event_id = l.timeline_event_id "
            + "join daily_records r on r.daily_record_id = e.daily_record_id "
            + "where l.timeline_item_id in (:itemIds)",
            nativeQuery = true)
    List<String> findOwnerSubjectIdsByItemIdIn(@Param("itemIds") Collection<Long> itemIds);

    /**
     * 연결 해제의 단건 junction 직접 DELETE. 영속성 컨텍스트를 거치지 않고 영향 행 수를 반환하므로,
     * 이미 지워진 행(같은 junction 동시 해제의 후발 요청)은 예외 대신 0으로 알려진다.
     */
    @Modifying
    @Query("delete from TimelineEventItem l "
            + "where l.id.timelineEventId = :eventId and l.id.timelineItemId = :itemId")
    int deleteByEventIdAndItemId(@Param("eventId") Long timelineEventId, @Param("itemId") Long timelineItemId);
}
