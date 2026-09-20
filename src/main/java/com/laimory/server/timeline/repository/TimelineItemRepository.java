package com.laimory.server.timeline.repository;

import com.laimory.server.timeline.entity.TimelineItem;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TimelineItemRepository extends JpaRepository<TimelineItem, Long> {

    /** PK 단건 조회 — 상속 {@code findById}와 달리 인터페이스 선언이라 transaction 없이 실행된다(#499). */
    Optional<TimelineItem> findByTimelineItemId(Long timelineItemId);

    /** PK IN 조회 — 상속 {@code findAllById}와 같은 이유로 인터페이스 선언이다(#499). 정렬 미보장. */
    List<TimelineItem> findByTimelineItemIdIn(Collection<Long> timelineItemIds);

    // append 시 이미 저장된 source item을 rawId로 제외하기 위한 projection 조회.
    // rawId만 select 한다(JSON payload를 든 전체 엔티티 로드 회피). 후보 itemIds·rawIds로 좁혀 전체 스캔을 막는다.
    @Query("select ti.rawId from TimelineItem ti where ti.timelineItemId in :itemIds and ti.rawId in :rawIds")
    List<String> findRawIdsByTimelineItemIdInAndRawIdIn(@Param("itemIds") Collection<Long> itemIds,
                                                        @Param("rawIds") Collection<String> rawIds);

    /**
     * 계정 삭제(#302)의 Item 일괄 제거 — junction은 FK CASCADE로 함께 사라진다.
     * record 삭제와 <b>같은 transaction</b>에서 호출해야 한다: record가 먼저 사라지면 junction도 함께
     * 사라져 이 Item들을 다시 특정할 경로가 없다({@code timeline_items}에는 owner 컬럼이 없다).
     */
    @Modifying
    @Query("delete from TimelineItem ti where ti.timelineItemId in :itemIds")
    int deleteAllByIdIn(@Param("itemIds") Collection<Long> itemIds);

    /** 담당 PK에서 한 배치만 일반 조회한다. 관측 표시는 후보 제외 조건이 아니다. */
    @Query(value = "select * from timeline_items i "
            + "where mod(i.timeline_item_id - 1, :totalWorkerCount) = :workerIndex "
            + "and not exists (select 1 from timeline_event_items l "
            + "                where l.timeline_item_id = i.timeline_item_id) "
            + "and not exists (select 1 from timeline_photo_delete_jobs j "
            + "                where j.timeline_item_id = i.timeline_item_id) "
            + "order by i.timeline_item_id limit :limit", nativeQuery = true)
    List<TimelineItem> findOrphanCandidates(@Param("workerIndex") int workerIndex,
                                            @Param("totalWorkerCount") int totalWorkerCount,
                                            @Param("limit") int limit);

    /** 선택한 PK 안의 미표시 고아만 기록한다. 재시도는 최초 관측 시각을 덮어쓰지 않는다. */
    @Modifying
    @Query(value = "update timeline_items i set modified_by = 'ORPHAN_SWEEPER', updated_at = :observedAt "
            + "where i.timeline_item_id in (:itemIds) "
            + "and (i.modified_by is null or i.modified_by <> 'ORPHAN_SWEEPER') "
            + "and not exists (select 1 from timeline_event_items l "
            + "                where l.timeline_item_id = i.timeline_item_id) "
            + "and not exists (select 1 from timeline_photo_delete_jobs j "
            + "                where j.timeline_item_id = i.timeline_item_id)", nativeQuery = true)
    int markOrphanObserved(@Param("itemIds") Collection<Long> itemIds,
                           @Param("observedAt") LocalDateTime observedAt);

    /** 현재 배치 밖을 포함해 담당 전체에서 72시간 이상 관측된 고아를 센다. */
    @Query(value = "select count(*) from timeline_items i "
            + "where mod(i.timeline_item_id - 1, :totalWorkerCount) = :workerIndex "
            + "and i.modified_by = 'ORPHAN_SWEEPER' and i.updated_at <= :staleBefore "
            + "and not exists (select 1 from timeline_event_items l "
            + "                where l.timeline_item_id = i.timeline_item_id) "
            + "and not exists (select 1 from timeline_photo_delete_jobs j "
            + "                where j.timeline_item_id = i.timeline_item_id)", nativeQuery = true)
    long countStaleObservedOrphans(@Param("workerIndex") int workerIndex,
                                   @Param("totalWorkerCount") int totalWorkerCount,
                                   @Param("staleBefore") LocalDateTime staleBefore);

}
