package com.laimory.server.timeline.repository;

import com.laimory.server.timeline.entity.TimelineEvent;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 조회 메서드는 이름에 {@code DeletedAtIsNull}을 담아 활성 Event(soft delete 안 된 행)만 읽는다(#568). */
public interface TimelineEventRepository extends JpaRepository<TimelineEvent, Long> {

    /** PK 단건 조회 — 상속 {@code findById}와 달리 인터페이스 선언이라 transaction 없이 실행된다(#499). */
    Optional<TimelineEvent> findByTimelineEventIdAndDeletedAtIsNull(Long timelineEventId);

    // 표시 순서 고정(DB 반환순 의존 제거): start_at, timeline_event_id 오름차순
    List<TimelineEvent> findByDailyRecordIdAndDeletedAtIsNullOrderByStartAtAscTimelineEventIdAsc(Long dailyRecordId);

    List<TimelineEvent> findByDailyRecordIdInAndDeletedAtIsNullOrderByDailyRecordIdAscStartAtAscTimelineEventIdAsc(
            Collection<Long> dailyRecordIds);

    /**
     * 활성 Event를 soft delete하고 장소명·주소를 지운다(#568 — 위치정보는 삭제 기록에 남기지 않는다).
     * 이미 삭제된 행은 조건에서 빠져 0을 반환하므로 동시 삭제의 첫 시각이 보존된다. bulk UPDATE는 JPA auditing을
     * 우회하므로 {@code updated_at}을 직접 채운다.
     */
    @Modifying
    @Query("update TimelineEvent e "
            + "set e.deletedAt = :now, e.place = null, e.address = null, e.updatedAt = :now "
            + "where e.timelineEventId = :timelineEventId and e.deletedAt is null")
    int softDelete(@Param("timelineEventId") Long timelineEventId, @Param("now") LocalDateTime now);

    /**
     * 삭제되는 DailyRecord의 활성 Event 전부를 record와 같은 시각으로 soft delete하고 장소명·주소를 지운다.
     * 먼저 개별 삭제된 Event는 조건에서 빠져 원래 삭제 시각을 유지한다 — 분석은 시각 비교로 둘을 구분한다.
     */
    @Modifying
    @Query("update TimelineEvent e "
            + "set e.deletedAt = :now, e.place = null, e.address = null, e.updatedAt = :now "
            + "where e.dailyRecordId = :dailyRecordId and e.deletedAt is null")
    int softDeleteByDailyRecordId(@Param("dailyRecordId") Long dailyRecordId, @Param("now") LocalDateTime now);
}
