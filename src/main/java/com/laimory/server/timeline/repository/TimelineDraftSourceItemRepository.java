package com.laimory.server.timeline.repository;

import com.laimory.server.timeline.entity.TimelineDraftSourceItem;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** timeline_draft_source_items 레포. task_id 단위 조회/삭제, 보관기간 초과 행 조회(cleanup용). */
public interface TimelineDraftSourceItemRepository extends JpaRepository<TimelineDraftSourceItem, Long> {

    @Query("select source from TimelineDraftSourceItem source "
            + "where source.taskId = :taskId order by source.timelineDraftSourceItemId asc")
    List<TimelineDraftSourceItem> findByTaskId(@Param("taskId") String taskId);

    @Modifying
    @Transactional
    void deleteByTaskId(String taskId);

    /**
     * task의 staging 행 중 rawId가 채택 목록에 든 행만 삭제한다(결과 저장 transaction 전용).
     * 채택되지 않은 행은 남겨 retention cleanup이 정리한다.
     */
    @Modifying
    @Transactional
    void deleteByTaskIdAndRawIdIn(String taskId, Collection<String> rawIds);

    @Query(value = "select * from timeline_draft_source_items "
            + "where created_at < :cutoff and cleanup_available_at <= :eligibleAt "
            + "order by cleanup_available_at, created_at, timeline_draft_source_item_id "
            + "limit :limit for update skip locked",
            nativeQuery = true)
    List<TimelineDraftSourceItem> findExpiredForUpdateSkipLocked(
            @Param("cutoff") LocalDateTime cutoff,
            @Param("eligibleAt") LocalDateTime eligibleAt,
            @Param("limit") int limit);

    @Modifying
    @Query("update TimelineDraftSourceItem source "
            + "set source.cleanupAvailableAt = :nextAvailableAt "
            + "where source.timelineDraftSourceItemId in :ids")
    int deferCleanupUntil(@Param("ids") Collection<Long> ids,
                          @Param("nextAvailableAt") LocalDateTime nextAvailableAt);

    @Modifying
    @Transactional
    @Query("delete from TimelineDraftSourceItem source where source.timelineDraftSourceItemId in :ids")
    int deleteAllByIdIn(@Param("ids") Collection<Long> ids);

}
