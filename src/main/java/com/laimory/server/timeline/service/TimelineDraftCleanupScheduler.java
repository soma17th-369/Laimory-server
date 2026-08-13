package com.laimory.server.timeline.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.timeline.ItemType;
import com.laimory.server.timeline.entity.TimelineDraftSourceItem;
import com.laimory.server.timeline.payload.PhotoPayload;
import com.laimory.server.timeline.photo.PhotoObjectKeys;
import com.laimory.server.timeline.photo.S3PhotoStorageService;
import com.laimory.server.timeline.photo.S3PhotoStorageService.BatchDeleteResult;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 여러 process/thread가 만료 draft source를 bounded batch로 나눠 정리하는 worker trigger. */
@Slf4j
@Component
public class TimelineDraftCleanupScheduler {

    private final TimelineDraftSourceItemService timelineDraftSourceItemService;
    private final S3PhotoStorageService s3PhotoStorageService;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TimelineDraftCleanupWorkerProperties properties;
    private final TimelineDraftCleanupMetrics metrics;
    private final TaskExecutor workerExecutor;
    private final AtomicBoolean runActive = new AtomicBoolean();

    public TimelineDraftCleanupScheduler(
            TimelineDraftSourceItemService timelineDraftSourceItemService,
            S3PhotoStorageService s3PhotoStorageService,
            ObjectMapper objectMapper,
            Clock clock,
            TimelineDraftCleanupWorkerProperties properties,
            TimelineDraftCleanupMetrics metrics,
            @Qualifier("timelineDraftCleanupWorkerExecutor") TaskExecutor workerExecutor) {
        this.timelineDraftSourceItemService = timelineDraftSourceItemService;
        this.s3PhotoStorageService = s3PhotoStorageService;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.properties = properties;
        this.metrics = metrics;
        this.workerExecutor = workerExecutor;
    }

    @Scheduled(
            cron = "${app.draft.cleanup-cron:0 0 4 * * *}",
            zone = "${app.draft.cleanup-zone:Asia/Seoul}")
    public void cleanupExpiredDrafts() {
        if (!properties.isWorkerEnabled()) {
            return;
        }
        if (!runActive.compareAndSet(false, true)) {
            log.info("draft cleanup 이전 run이 아직 실행 중이어서 trigger를 건너뜀");
            return;
        }

        // created_at을 쓰는 JPA auditing/JDBC batch와 같은 application local clock 계약으로 보관기간을 계산한다.
        LocalDateTime cutoff = LocalDateTime.now(clock).minusDays(properties.getRetentionDays());
        ScheduledWorkerRunBudget budget = new ScheduledWorkerRunBudget(
                properties.getMaxBatchesPerRun(), properties.getMaxRunDuration());
        AtomicInteger remainingSlots = new AtomicInteger(properties.getConcurrency());
        for (int slot = 0; slot < properties.getConcurrency(); slot++) {
            try {
                workerExecutor.execute(() -> runWorkerSlot(cutoff, budget, remainingSlots));
            } catch (RuntimeException exception) {
                log.warn("draft cleanup worker task 제출 실패: exceptionType={}",
                        exception.getClass().getSimpleName());
                workerSlotFinished(remainingSlots);
            }
        }
    }

    private void runWorkerSlot(
            LocalDateTime cutoff,
            ScheduledWorkerRunBudget budget,
            AtomicInteger remainingSlots) {
        try {
            while (budget.tryAcquireBatch()) {
                List<TimelineDraftSourceItem> rows;
                try {
                    rows = timelineDraftSourceItemService.claimExpired(cutoff, properties.getBatchSize());
                } catch (RuntimeException exception) {
                    log.warn("draft cleanup claim 실패: exceptionType={}", exception.getClass().getSimpleName());
                    return;
                }
                if (rows.isEmpty()) {
                    return;
                }
                metrics.recordClaimed(rows.size());
                processClaimedBatch(rows);
            }
        } finally {
            workerSlotFinished(remainingSlots);
        }
    }

    private void workerSlotFinished(AtomicInteger remainingSlots) {
        if (remainingSlots.decrementAndGet() == 0) {
            runActive.set(false);
        }
    }

    private void processClaimedBatch(List<TimelineDraftSourceItem> rows) {
        Set<Long> deletableIds = new LinkedHashSet<>();
        Map<Long, String> photoKeyByRowId = new LinkedHashMap<>();
        for (TimelineDraftSourceItem row : rows) {
            if (row.getItemType() != ItemType.PHOTO) {
                deletableIds.add(row.getTimelineDraftSourceItemId());
                continue;
            }
            String objectKey = photoObjectKeyOrNull(row);
            if (objectKey == null) {
                deletableIds.add(row.getTimelineDraftSourceItemId());
            } else {
                photoKeyByRowId.put(row.getTimelineDraftSourceItemId(), objectKey);
            }
        }

        if (!photoKeyByRowId.isEmpty()) {
            List<String> distinctObjectKeys = new ArrayList<>(new LinkedHashSet<>(photoKeyByRowId.values()));
            try {
                BatchDeleteResult result = s3PhotoStorageService.deleteAll(distinctObjectKeys);
                Set<String> deletedObjectKeys = result.deletedObjectKeys();
                photoKeyByRowId.forEach((rowId, objectKey) -> {
                    if (deletedObjectKeys.contains(objectKey)) {
                        deletableIds.add(rowId);
                    }
                });
            } catch (RuntimeException exception) {
                log.warn("draft PHOTO S3 batch 삭제 실패(행 유지): requested={} exceptionType={}",
                        distinctObjectKeys.size(), exception.getClass().getSimpleName());
            }
        }

        int completed = 0;
        boolean databaseDeleteFailed = false;
        try {
            completed = timelineDraftSourceItemService.deleteClaimed(deletableIds);
        } catch (RuntimeException exception) {
            databaseDeleteFailed = true;
            log.warn("draft cleanup DB 삭제 실패(행 유지): requested={} exceptionType={}",
                    deletableIds.size(), exception.getClass().getSimpleName());
        }
        metrics.recordCompleted(completed);
        // bulk delete 수가 작으면 final 결과 transaction이 이미 staging row를 채택·삭제했을 수 있다.
        // 다음 실행까지 실제로 남겨 둔 것은 S3 성공을 확인하지 못한 PHOTO row뿐이다.
        int deferred = databaseDeleteFailed ? rows.size() : rows.size() - deletableIds.size();
        metrics.recordDeferred(deferred);
        log.info("draft cleanup batch 완료: claimed={} completed={} deferred={}",
                rows.size(), completed, deferred);
    }

    /** payload가 깨졌거나 filename이 없으면 기존 정책대로 S3 orphan을 수용하고 {@code null}을 반환한다. */
    private String photoObjectKeyOrNull(TimelineDraftSourceItem row) {
        PhotoPayload photo;
        try {
            photo = objectMapper.treeToValue(row.getPayload(), PhotoPayload.class);
        } catch (JsonProcessingException | RuntimeException exception) {
            log.warn("PHOTO payload 파싱 실패, S3 삭제 건너뜀: id={}",
                    row.getTimelineDraftSourceItemId());
            return null;
        }
        if (photo == null || photo.filename() == null || photo.filename().isBlank()) {
            log.warn("PHOTO payload filename 없음, S3 삭제 건너뜀: id={}",
                    row.getTimelineDraftSourceItemId());
            return null;
        }
        return PhotoObjectKeys.subjectFullKey(photo.filename(), row.getSubjectId());
    }
}
