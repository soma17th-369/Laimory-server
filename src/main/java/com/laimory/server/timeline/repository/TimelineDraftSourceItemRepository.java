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

    /**
     * 보관기간 초과(created_at < cutoff) 행을 조회한다(cleanup 스케줄러용). 행마다 S3 사진 객체를 먼저 지운 뒤
     * 개별 삭제하므로 bulk delete가 아니라 조회로 받는다.
     */
    List<TimelineDraftSourceItem> findByCreatedAtBefore(LocalDateTime cutoff);
}
