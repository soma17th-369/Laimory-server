package com.laimory.server.timeline.service;

import com.laimory.server.timeline.entity.TimelineDraftSourceItem;
import com.laimory.server.timeline.repository.TimelineDraftSourceItemBatchRepository;
import com.laimory.server.timeline.repository.TimelineDraftSourceItemRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
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
    private static final ZoneId CLEANUP_ZONE = ZoneId.of("Asia/Seoul");

    private final TimelineDraftSourceItemRepository timelineDraftSourceItemRepository;
    private final TimelineDraftSourceItemBatchRepository timelineDraftSourceItemBatchRepository;
    private final Clock clock;

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

    /** 만료되고 eligible한 행을 짧게 claim하고 다음 일일 실행 전까지 재선택되지 않게 미룬다. */
    @Transactional
    public List<TimelineDraftSourceItem> claimExpired(LocalDateTime cutoff, int limit) {
        if (limit < 1 || limit > MAX_CLEANUP_BATCH_SIZE) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_CLEANUP_BATCH_SIZE);
        }
        ZonedDateTime now = ZonedDateTime.ofInstant(clock.instant(), CLEANUP_ZONE);
        LocalDateTime eligibleAt = now.toLocalDateTime();
        LocalDateTime nextAvailableAt = now.toLocalDate().plusDays(1).atStartOfDay();
        List<TimelineDraftSourceItem> rows = timelineDraftSourceItemRepository
                .findExpiredForUpdateSkipLocked(cutoff, eligibleAt, limit);
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Long> ids = rows.stream()
                .map(TimelineDraftSourceItem::getTimelineDraftSourceItemId)
                .toList();
        int deferred = timelineDraftSourceItemRepository.deferCleanupUntil(ids, nextAvailableAt);
        if (deferred != ids.size()) {
            throw new IllegalStateException("draft cleanup claim count mismatch");
        }
        return List.copyOf(rows);
    }

    /** S3 삭제 성공 또는 S3 삭제가 필요 없는 만료 행을 한 transaction에서 지운다. */
    public int deleteClaimed(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        return timelineDraftSourceItemRepository.deleteAllByIdIn(ids);
    }
}
