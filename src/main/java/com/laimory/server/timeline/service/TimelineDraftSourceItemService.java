package com.laimory.server.timeline.service;

import com.laimory.server.timeline.entity.TimelineDraftSourceItem;
import com.laimory.server.timeline.repository.TimelineDraftSourceItemBatchRepository;
import com.laimory.server.timeline.repository.TimelineDraftSourceItemRepository;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** timeline_draft_source_items leaf 서비스. INSERT는 JDBC batch, 조회·삭제는 JPA repository를 사용한다. */
@Service
@RequiredArgsConstructor
public class TimelineDraftSourceItemService {

    private static final int MAX_CLEANUP_BATCH_SIZE = 1_000;

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

    /** 자기 담당의 만료 행을 한 번 조회한다. 실패한 행은 다음 일일 실행에 다시 조회된다. */
    @Transactional(readOnly = true)
    public List<TimelineDraftSourceItem> findExpired(LocalDateTime cutoff, int workerIndex,
                                                     int totalWorkerCount, int limit) {
        if (limit < 1 || limit > MAX_CLEANUP_BATCH_SIZE) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_CLEANUP_BATCH_SIZE);
        }
        return timelineDraftSourceItemRepository.findExpired(cutoff, workerIndex, totalWorkerCount, limit);
    }

    /** S3 삭제 성공 또는 S3 삭제가 필요 없는 만료 행을 한 transaction에서 지운다. */
    public int deleteExpired(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        return timelineDraftSourceItemRepository.deleteAllByIdIn(ids);
    }
}
