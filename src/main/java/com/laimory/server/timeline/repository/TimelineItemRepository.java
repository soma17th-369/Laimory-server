package com.laimory.server.timeline.repository;

import com.laimory.server.timeline.entity.TimelineItem;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TimelineItemRepository extends JpaRepository<TimelineItem, Long> {

    /** Event-Item 연결 해제의 직렬화 지점(SELECT ... FOR UPDATE) — 같은 Item 동시 해제의 마지막 참조 오판 방지. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select ti from TimelineItem ti where ti.timelineItemId = :itemId")
    Optional<TimelineItem> findByIdForUpdate(@Param("itemId") Long timelineItemId);

    // append 시 이미 저장된 source item을 rawId로 제외하기 위한 projection 조회.
    // rawId만 select 한다(JSON payload를 든 전체 엔티티 로드 회피). 후보 itemIds·rawIds로 좁혀 전체 스캔을 막는다.
    @Query("select ti.rawId from TimelineItem ti where ti.timelineItemId in :itemIds and ti.rawId in :rawIds")
    List<String> findRawIdsByTimelineItemIdInAndRawIdIn(@Param("itemIds") Collection<Long> itemIds,
                                                        @Param("rawIds") Collection<String> rawIds);

    /** Event PATCH PHOTO append의 rawId type/reuse/no-op 분류용 full entity 조회. */
    @Query("select ti from TimelineItem ti where ti.timelineItemId in :itemIds and ti.rawId in :rawIds")
    List<TimelineItem> findByTimelineItemIdInAndRawIdIn(@Param("itemIds") Collection<Long> itemIds,
                                                        @Param("rawIds") Collection<String> rawIds);
}
