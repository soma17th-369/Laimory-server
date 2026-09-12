package com.laimory.server.timeline.repository;

import com.laimory.server.timeline.entity.TimelineItem;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TimelineItemRepository extends JpaRepository<TimelineItem, Long> {

    // append 시 이미 저장된 source item을 rawId로 제외하기 위한 projection 조회.
    // rawId만 select 한다(JSON payload를 든 전체 엔티티 로드 회피). 후보 itemIds·rawIds로 좁혀 전체 스캔을 막는다.
    @Query("select ti.rawId from TimelineItem ti where ti.timelineItemId in :itemIds and ti.rawId in :rawIds")
    List<String> findRawIdsByTimelineItemIdInAndRawIdIn(@Param("itemIds") Collection<Long> itemIds,
                                                        @Param("rawIds") Collection<String> rawIds);

    /** 수동 PHOTO 추가(Event PATCH·Event 생성 POST)의 rawId type/reuse/no-op 분류용 full entity 조회. */
    /**
     * 계정 삭제(#302)의 Item 일괄 제거 — junction은 FK CASCADE로 함께 사라진다.
     * record 삭제와 <b>같은 transaction</b>에서 호출해야 한다: record가 먼저 사라지면 junction도 함께
     * 사라져 이 Item들을 다시 특정할 경로가 없다({@code timeline_items}에는 owner 컬럼이 없다).
     */
    @Modifying
    @Query("delete from TimelineItem ti where ti.timelineItemId in :itemIds")
    int deleteAllByIdIn(@Param("itemIds") Collection<Long> itemIds);

    @Query("select ti from TimelineItem ti where ti.timelineItemId in :itemIds and ti.rawId in :rawIds")
    List<TimelineItem> findByTimelineItemIdInAndRawIdIn(@Param("itemIds") Collection<Long> itemIds,
                                                        @Param("rawIds") Collection<String> rawIds);

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

    /** 재연결 transaction에서 관측을 끝낸다. 공통 감사나 다른 modified_by 값은 바꾸지 않는다. */
    @Modifying
    @Query(value = "update timeline_items set modified_by = null, updated_at = :linkedAt "
            + "where timeline_item_id in (:itemIds) and modified_by = 'ORPHAN_SWEEPER'", nativeQuery = true)
    int clearOrphanObservation(@Param("itemIds") Collection<Long> itemIds,
                               @Param("linkedAt") LocalDateTime linkedAt);

    /**
     * 주어진 filename을 참조하면서 <b>junction이 살아 있는</b> PHOTO Item의 full object key 집합.
     *
     * <p>key를 저장된 {@code photoUrl}이 아니라 <b>소유 subject에서 직접</b> 계산한다 —
     * {@code SHA2(UNHEX(REPLACE(subject_id,'-','')), 256)}가
     * {@code PhotoObjectKeys.subjectNamespace}(UUID canonical 16바이트의 SHA-256 hex)와 같은 값이다.
     * 그래서 살아 있는 Item의 {@code photoUrl}이 손상돼 있어도 이 판정은 영향을 받지 않는다 — 삭제하면
     * 안 되는 객체를 놓치지 않는 것이 이 조회의 존재 이유다.
     *
     * <p>filename은 index를 쓸 수 없는 JSON 조건이라 coarse filter일 뿐이고, 최종 판정은 호출부가
     * 반환된 full key와 정확히 비교해서 한다(filename만 같고 namespace가 다른 남의 Item 차단).
     */
    @Query(value = "select distinct concat("
            + "  sha2(unhex(replace(d.subject_id, '-', '')), 256), '/photos/', "
            + "  json_unquote(json_extract(i.payload, '$.filename'))) "
            + "from timeline_items i "
            + "join timeline_event_items l on l.timeline_item_id = i.timeline_item_id "
            + "join timeline_events e on e.timeline_event_id = l.timeline_event_id "
            + "join daily_records d on d.daily_record_id = e.daily_record_id "
            + "where i.item_type = 'PHOTO' "
            + "and json_unquote(json_extract(i.payload, '$.filename')) in (:filenames)",
            nativeQuery = true)
    List<String> findLiveObjectKeysByFilenames(@Param("filenames") Collection<String> filenames);

    /**
     * 주어진 filename을 참조하는 <b>junction 없는</b> PHOTO Item의 {@code (id, photoUrl)}.
     * 같은 key를 공유하는 orphan 그룹에서 job 소유자(최소 id)를 정하는 입력이다. 이쪽은 subject를 잃어
     * {@code photoUrl}이 유일한 key 복원 경로다.
     */
    @Query(value = "select i.timeline_item_id as timelineItemId, "
            + "json_unquote(json_extract(i.payload, '$.photoUrl')) as photoUrl "
            + "from timeline_items i "
            + "where i.item_type = 'PHOTO' "
            + "and json_unquote(json_extract(i.payload, '$.filename')) in (:filenames) "
            + "and not exists (select 1 from timeline_event_items l "
            + "                where l.timeline_item_id = i.timeline_item_id)",
            nativeQuery = true)
    List<OrphanPhotoKeyRow> findUnlinkedPhotoKeysByFilenames(@Param("filenames") Collection<String> filenames);

    /** {@link #findUnlinkedPhotoKeysByFilenames} projection. */
    interface OrphanPhotoKeyRow {

        Long getTimelineItemId();

        String getPhotoUrl();
    }
}
