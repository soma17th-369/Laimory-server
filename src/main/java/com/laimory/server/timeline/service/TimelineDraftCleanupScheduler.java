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
    private final TaskExecutor workerExecutor;
    private final AtomicBoolean runActive = new AtomicBoolean();

    public TimelineDraftCleanupScheduler(
            TimelineDraftSourceItemService timelineDraftSourceItemService,
            S3PhotoStorageService s3PhotoStorageService,
            ObjectMapper objectMapper,
            Clock clock,
            TimelineDraftCleanupWorkerProperties properties,
            @Qualifier("timelineDraftCleanupWorkerExecutor") TaskExecutor workerExecutor) {
        this.timelineDraftSourceItemService = timelineDraftSourceItemService;
        this.s3PhotoStorageService = s3PhotoStorageService;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.properties = properties;
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
        RunSummary summary = new RunSummary();
        AtomicInteger remainingSlots = new AtomicInteger(properties.getConcurrency());
        log.info("draft cleanup run 시작: cutoff={}, retentionDays={}, batchSize={}, concurrency={}, "
                        + "maxBatches={}, maxRunDurationMs={}",
                cutoff,
                properties.getRetentionDays(),
                properties.getBatchSize(),
                properties.getConcurrency(),
                properties.getMaxBatchesPerRun(),
                properties.getMaxRunDuration().toMillis());
        for (int slot = 0; slot < properties.getConcurrency(); slot++) {
            try {
                workerExecutor.execute(() -> runWorkerSlot(cutoff, budget, remainingSlots, summary));
            } catch (RuntimeException exception) {
                summary.recordWorkerError();
                log.warn("draft cleanup worker task 제출 실패: exceptionType={}",
                        exception.getClass().getSimpleName());
                workerSlotFinished(remainingSlots, summary);
            }
        }
    }

    private void runWorkerSlot(
            LocalDateTime cutoff,
            ScheduledWorkerRunBudget budget,
            AtomicInteger remainingSlots,
            RunSummary summary) {
        try {
            while (budget.tryAcquireBatch()) {
                List<TimelineDraftSourceItem> rows;
                try {
                    rows = timelineDraftSourceItemService.claimExpired(cutoff, properties.getBatchSize());
                } catch (RuntimeException exception) {
                    summary.recordWorkerError();
                    log.warn("draft cleanup claim 실패: exceptionType={}", exception.getClass().getSimpleName());
                    return;
                }
                if (rows.isEmpty()) {
                    return;
                }
                summary.record(processClaimedBatch(rows));
            }
        } finally {
            workerSlotFinished(remainingSlots, summary);
        }
    }

    private void workerSlotFinished(AtomicInteger remainingSlots, RunSummary summary) {
        if (remainingSlots.decrementAndGet() == 0) {
            runActive.set(false);
            summary.logCompleted();
        }
    }

    private BatchResult processClaimedBatch(List<TimelineDraftSourceItem> rows) {
        long startedAtNanos = System.nanoTime();
        Set<Long> deletableIds = new LinkedHashSet<>();
        Map<Long, String> photoKeyByRowId = new LinkedHashMap<>();
        int photoDeleteSkipped = 0;
        for (TimelineDraftSourceItem row : rows) {
            if (row.getItemType() != ItemType.PHOTO) {
                deletableIds.add(row.getTimelineDraftSourceItemId());
                continue;
            }
            String objectKey = photoObjectKeyOrNull(row);
            if (objectKey == null) {
                deletableIds.add(row.getTimelineDraftSourceItemId());
                photoDeleteSkipped++;
            } else {
                photoKeyByRowId.put(row.getTimelineDraftSourceItemId(), objectKey);
            }
        }

        int photoDeleteSucceeded = 0;
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
                photoDeleteSucceeded = (int) photoKeyByRowId.values().stream()
                        .filter(deletedObjectKeys::contains)
                        .count();
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
        // bulk delete 수가 작으면 final 결과 transaction이 이미 staging row를 채택·삭제했을 수 있다.
        int succeeded = databaseDeleteFailed ? 0 : deletableIds.size();
        int failed = rows.size() - succeeded;
        int alreadyAbsent = databaseDeleteFailed ? 0 : Math.max(0, deletableIds.size() - completed);
        int photoDeleteFailed = photoKeyByRowId.size() - photoDeleteSucceeded;
        long durationMs = elapsedMillis(startedAtNanos);
        BatchResult result = new BatchResult(
                rows.size(),
                succeeded,
                failed,
                completed,
                alreadyAbsent,
                photoKeyByRowId.size(),
                photoDeleteSucceeded,
                photoDeleteFailed,
                photoDeleteSkipped,
                databaseDeleteFailed,
                durationMs);
        log.info("draft cleanup batch 완료: claimed={}, succeeded={}, failed={}, deleted={}, "
                        + "alreadyAbsent={}, photoDeleteRequested={}, photoDeleteSucceeded={}, "
                        + "photoDeleteFailed={}, photoDeleteSkipped={}, dbDeleteFailed={}, durationMs={}",
                result.claimed(),
                result.succeeded(),
                result.failed(),
                result.deleted(),
                result.alreadyAbsent(),
                result.photoDeleteRequested(),
                result.photoDeleteSucceeded(),
                result.photoDeleteFailed(),
                result.photoDeleteSkipped(),
                result.databaseDeleteFailed(),
                result.durationMs());
        return result;
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

    private static long elapsedMillis(long startedAtNanos) {
        return Math.max(0, (System.nanoTime() - startedAtNanos) / 1_000_000);
    }

    private record BatchResult(
            int claimed,
            int succeeded,
            int failed,
            int deleted,
            int alreadyAbsent,
            int photoDeleteRequested,
            int photoDeleteSucceeded,
            int photoDeleteFailed,
            int photoDeleteSkipped,
            boolean databaseDeleteFailed,
            long durationMs) {
    }

    private static final class RunSummary {

        private final long startedAtNanos = System.nanoTime();
        private int batches;
        private int claimed;
        private int succeeded;
        private int failed;
        private int deleted;
        private int alreadyAbsent;
        private int photoDeleteRequested;
        private int photoDeleteSucceeded;
        private int photoDeleteFailed;
        private int photoDeleteSkipped;
        private int databaseErrors;
        private int workerErrors;

        private synchronized void record(BatchResult result) {
            batches++;
            claimed += result.claimed();
            succeeded += result.succeeded();
            failed += result.failed();
            deleted += result.deleted();
            alreadyAbsent += result.alreadyAbsent();
            photoDeleteRequested += result.photoDeleteRequested();
            photoDeleteSucceeded += result.photoDeleteSucceeded();
            photoDeleteFailed += result.photoDeleteFailed();
            photoDeleteSkipped += result.photoDeleteSkipped();
            if (result.databaseDeleteFailed()) {
                databaseErrors++;
            }
        }

        private synchronized void recordWorkerError() {
            workerErrors++;
        }

        private synchronized void logCompleted() {
            log.info("draft cleanup run 완료: batches={}, claimed={}, succeeded={}, failed={}, deleted={}, "
                            + "alreadyAbsent={}, photoDeleteRequested={}, photoDeleteSucceeded={}, "
                            + "photoDeleteFailed={}, photoDeleteSkipped={}, databaseErrors={}, "
                            + "workerErrors={}, durationMs={}",
                    batches,
                    claimed,
                    succeeded,
                    failed,
                    deleted,
                    alreadyAbsent,
                    photoDeleteRequested,
                    photoDeleteSucceeded,
                    photoDeleteFailed,
                    photoDeleteSkipped,
                    databaseErrors,
                    workerErrors,
                    elapsedMillis(startedAtNanos));
        }
    }
}
