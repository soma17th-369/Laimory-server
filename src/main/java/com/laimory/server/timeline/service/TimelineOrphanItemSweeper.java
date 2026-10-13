package com.laimory.server.timeline.service;

import com.laimory.server.common.logging.LogSanitizer;
import com.laimory.server.timeline.photo.S3PhotoStorageService;
import com.laimory.server.timeline.photo.S3PhotoStorageService.BatchDeleteResult;
import com.laimory.server.timeline.service.TimelineOrphanItemSweepService.PhotoDeletion;
import com.laimory.server.timeline.service.TimelineOrphanItemSweepService.SweepBatchResult;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 고정 담당 slot마다 한 배치를 처리한다. DB 처리는 스케줄 스레드에서 slot을 순차 실행하고, 유효 PHOTO의 S3 삭제는
 * 전용 executor에 넘긴 뒤 응답을 기다리지 않는다(#551). 결과를 받은 executor 스레드가 S3 삭제가 확인된 PHOTO
 * Item만 지운다 — S3 실패·응답 누락·예외·제출 거절·프로세스 종료는 모두 Item을 남겨 다음 실행이 다시 집는다.
 */
@Slf4j
@Component
public class TimelineOrphanItemSweeper {

    private static final int MAX_LOGGED_ERROR_CODE_LENGTH = 64;

    private final TimelineOrphanItemSweepService sweepService;
    private final S3PhotoStorageService s3PhotoStorageService;
    private final TimelineOrphanItemSweeperProperties properties;
    private final TaskExecutor photoDeleteExecutor;
    private final AtomicBoolean runActive = new AtomicBoolean();

    public TimelineOrphanItemSweeper(
            TimelineOrphanItemSweepService sweepService,
            S3PhotoStorageService s3PhotoStorageService,
            TimelineOrphanItemSweeperProperties properties,
            @Qualifier("timelineOrphanSweepExecutor") TaskExecutor photoDeleteExecutor) {
        this.sweepService = sweepService;
        this.s3PhotoStorageService = s3PhotoStorageService;
        this.properties = properties;
        this.photoDeleteExecutor = photoDeleteExecutor;
    }

    @Scheduled(
            cron = "${app.timeline.orphan-sweep.cron:0 30 3 * * *}",
            zone = "${app.timeline.orphan-sweep.zone:Asia/Seoul}")
    public void sweepOrphanItems() {
        if (!properties.isWorkerEnabled()) {
            return;
        }
        if (!runActive.compareAndSet(false, true)) {
            log.info("orphan 스위퍼 이전 run이 아직 실행 중이어서 trigger를 건너뜀");
            return;
        }
        try {
            for (int localIndex = 0; localIndex < properties.getWorkerCount(); localIndex++) {
                runWorkerSlot(properties.getWorkerIndex(localIndex));
            }
        } finally {
            // S3 삭제 task의 완료는 기다리지 않는다. 다음 trigger는 하루 뒤다.
            runActive.set(false);
        }
    }

    private void runWorkerSlot(int workerIndex) {
        try {
            List<Long> ids = sweepService.observeBatch(
                    workerIndex, properties.getTotalWorkerCount(), properties.getBatchSize());
            if (!ids.isEmpty()) {
                SweepBatchResult result = sweepService.sweepBatch(ids);
                log.info("orphan 스위퍼 batch 완료: workerIndex={} selected={} revalidationDropped={} "
                                + "photoRequested={} invalidDeleted={} nonPhotoDeleted={}",
                        workerIndex, result.selected(), result.revalidationDropped(),
                        result.photoDeletions().size(), result.invalidDeleted(), result.nonPhotoDeleted());
                requestPhotoDeletion(workerIndex, result.photoDeletions());
            }
        } catch (RuntimeException exception) {
            log.warn("orphan 스위퍼 batch 실패(다음 실행에서 재시도): workerIndex={} exceptionType={}",
                    workerIndex, exception.getClass().getSimpleName());
        } finally {
            // 처리 commit/rollback 뒤 transaction 없이 담당 전체를 센다. 다음 배치를 처리하지 않는다.
            try {
                long count = sweepService.countStaleObservedOrphans(workerIndex, properties.getTotalWorkerCount());
                if (count > 0) {
                    log.error("orphan Item 최초 관측 후 72시간 잔존: workerIndex={} count={}", workerIndex, count);
                }
            } catch (RuntimeException exception) {
                log.warn("orphan 잔존 집계 실패: workerIndex={} exceptionType={}",
                        workerIndex, exception.getClass().getSimpleName());
            }
        }
    }

    private void requestPhotoDeletion(int workerIndex, List<PhotoDeletion> photoDeletions) {
        if (photoDeletions.isEmpty()) {
            return;
        }
        try {
            photoDeleteExecutor.execute(() -> deletePhotos(workerIndex, photoDeletions));
        } catch (RuntimeException exception) {
            log.warn("orphan PHOTO S3 삭제 제출 실패(Item 유지, 다음 실행에서 재시도): workerIndex={} requested={} "
                            + "exceptionType={}",
                    workerIndex, photoDeletions.size(), exception.getClass().getSimpleName());
        }
    }

    /**
     * executor 스레드에서 S3 삭제를 요청하고, 삭제가 확인된 key의 Item만 지운다. 로그의 requested·s3Succeeded·
     * s3Failed·unreported는 서로 다른 key 수(합 = requested), itemsDeleted는 Item 수다. key는 로그에 싣지 않는다.
     */
    private void deletePhotos(int workerIndex, List<PhotoDeletion> photoDeletions) {
        long startedAtNanos = System.nanoTime();
        List<String> objectKeys = photoDeletions.stream().map(PhotoDeletion::objectKey).distinct().toList();
        BatchDeleteResult result;
        try {
            result = s3PhotoStorageService.deleteAll(objectKeys);
        } catch (RuntimeException exception) {
            log.warn("orphan PHOTO S3 삭제 실패(Item 유지, 다음 실행에서 재시도): workerIndex={} requested={} "
                            + "durationMs={} exceptionType={}",
                    workerIndex, objectKeys.size(), elapsedMillis(startedAtNanos),
                    exception.getClass().getSimpleName());
            return;
        }

        Set<String> deletedObjectKeys = result.deletedObjectKeys();
        List<Long> deletedItemIds = photoDeletions.stream()
                .filter(photo -> deletedObjectKeys.contains(photo.objectKey()))
                .map(PhotoDeletion::timelineItemId)
                .toList();
        String itemDeleteExceptionType = null;
        if (!deletedItemIds.isEmpty()) {
            try {
                sweepService.deletePhotoItems(deletedItemIds);
            } catch (RuntimeException exception) {
                itemDeleteExceptionType = exception.getClass().getSimpleName();
            }
        }

        Map<String, Long> errorCodeCounts = result.errorCodeByObjectKey().values().stream()
                .map(code -> LogSanitizer.sanitize(code, MAX_LOGGED_ERROR_CODE_LENGTH))
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        if (itemDeleteExceptionType != null) {
            // S3는 이미 지워졌다. Item이 남아 다음 실행이 다시 요청하면 없는 key라 Deleted로 수렴한다.
            log.warn("orphan PHOTO Item 삭제 실패(다음 실행에서 수렴): workerIndex={} requested={} s3Succeeded={} "
                            + "s3Failed={} unreported={} itemsDeleted=0 durationMs={} errorCodes={} exceptionType={}",
                    workerIndex, objectKeys.size(), deletedObjectKeys.size(), result.errorCodeByObjectKey().size(),
                    result.unreportedObjectKeys().size(), elapsedMillis(startedAtNanos), errorCodeCounts,
                    itemDeleteExceptionType);
            return;
        }
        log.info("orphan PHOTO S3 삭제 완료: workerIndex={} requested={} s3Succeeded={} s3Failed={} unreported={} "
                        + "itemsDeleted={} durationMs={} errorCodes={}",
                workerIndex, objectKeys.size(), deletedObjectKeys.size(), result.errorCodeByObjectKey().size(),
                result.unreportedObjectKeys().size(), deletedItemIds.size(), elapsedMillis(startedAtNanos),
                errorCodeCounts);
    }

    private static long elapsedMillis(long startedAtNanos) {
        return Math.max(0, (System.nanoTime() - startedAtNanos) / 1_000_000);
    }
}
