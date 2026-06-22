package com.laimory.server.timeline.repository;

import com.laimory.server.timeline.entity.TimelineDraftSourceItem;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

/** timeline_draft_source_items 레포. task_id 단위 조회/삭제, 보관기간 초과 행 cleanup 삭제. */
public interface TimelineDraftSourceItemRepository extends JpaRepository<TimelineDraftSourceItem, Long> {

    List<TimelineDraftSourceItem> findByTaskId(String taskId);

    @Modifying
    @Transactional
    void deleteByTaskId(String taskId);

    @Modifying
    @Transactional
    void deleteByCreatedAtBefore(LocalDateTime cutoff);
}
