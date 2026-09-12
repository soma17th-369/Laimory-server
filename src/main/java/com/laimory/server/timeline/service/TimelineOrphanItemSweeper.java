package com.laimory.server.timeline.service;

import com.laimory.server.timeline.service.TimelineOrphanItemSweepService.SweepBatchResult;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 고정 담당 slot마다 한 배치를 처리한다. 외부 I/O가 없어 스케줄 스레드에서 slot을 순차 실행한다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class TimelineOrphanItemSweeper {

    private final TimelineOrphanItemSweepService sweepService;
    private final TimelineOrphanItemSweeperProperties properties;
    private final AtomicBoolean runActive = new AtomicBoolean();

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
                                + "photoScheduled={} photoAlreadyJob={} keyShared={} invalidDeleted={} nonPhotoDeleted={}",
                        workerIndex, result.selected(), result.revalidationDropped(), result.photoScheduled(),
                        result.photoAlreadyJob(), result.keyShared(), result.invalidDeleted(), result.nonPhotoDeleted());
            }
        } catch (RuntimeException exception) {
            log.warn("orphan 스위퍼 batch 실패(다음 실행에서 재시도): workerIndex={} exceptionType={}",
                    workerIndex, exception.getClass().getSimpleName());
        } finally {
            // 처리 commit/rollback 뒤 새 transaction에서 담당 전체를 센다. 다음 배치를 처리하지 않는다.
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
}
