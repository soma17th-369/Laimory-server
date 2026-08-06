package com.laimory.server.timeline.service;

import com.laimory.server.timeline.entity.TimelineDraftSourceItem;
import com.laimory.server.timeline.repository.TimelineDraftSourceItemBatchRepository;
import com.laimory.server.timeline.repository.TimelineDraftSourceItemRepository;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** timeline_draft_source_items leaf 서비스. INSERT는 JDBC batch, 조회·삭제는 JPA repository를 사용한다. */
@Service
@RequiredArgsConstructor
public class TimelineDraftSourceItemService {

    private final TimelineDraftSourceItemRepository timelineDraftSourceItemRepository;
    private final TimelineDraftSourceItemBatchRepository timelineDraftSourceItemBatchRepository;

    public void saveAll(List<TimelineDraftSourceItem> items) {
        timelineDraftSourceItemBatchRepository.insertAll(items);
    }

    public List<TimelineDraftSourceItem> findByTaskId(String taskId) {
        return timelineDraftSourceItemRepository.findByTaskId(taskId);
    }

    public void deleteByTaskId(String taskId) {
        timelineDraftSourceItemRepository.deleteByTaskId(taskId);
    }

    /**
     * AI가 채택한 rawId의 staging 행만 삭제한다(결과 저장 transaction 안에서 호출). 채택되지 않은 행은
     * 남겨 retention cleanup이 정리한다. 빈 입력이면 아무것도 하지 않는다(빈 IN 쿼리 회피).
     */
    public void deleteAdopted(String taskId, Collection<String> rawIds) {
        if (rawIds.isEmpty()) {
            return;
        }
        timelineDraftSourceItemRepository.deleteByTaskIdAndRawIdIn(taskId, rawIds);
    }

    /** 보관기간 초과(created_at < cutoff) draft 행을 조회한다(cleanup 스케줄러가 행별 S3 삭제 후 개별 삭제). */
    public List<TimelineDraftSourceItem> findCreatedBefore(LocalDateTime cutoff) {
        return timelineDraftSourceItemRepository.findByCreatedAtBefore(cutoff);
    }

    /** draft 행 하나를 PK로 삭제한다(cleanup이 S3 객체 삭제 성공한 행만 지울 때 사용). */
    public void deleteById(Long id) {
        timelineDraftSourceItemRepository.deleteById(id);
    }
}
