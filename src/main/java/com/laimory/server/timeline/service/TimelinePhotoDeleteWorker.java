package com.laimory.server.timeline.service;

import com.laimory.server.common.logging.LogSanitizer;
import com.laimory.server.timeline.entity.TimelinePhotoDeleteJob;
import com.laimory.server.timeline.photo.S3PhotoStorageService;
import com.laimory.server.timeline.photo.S3PhotoStorageService.BatchDeleteResult;
import io.micrometer.core.instrument.Timer;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * MySQL PHOTO delete-job을 매일 oldest-first로 처리하는 현재 REST 애플리케이션의 단일 worker.
 *
 * <p>state/lease 없이 한 환경에서 worker-enabled 프로세스 하나만 실행한다. S3 호출은 DB transaction 밖이며
 * 성공이 확인된 job과 원문 PHOTO Item만 짧은 별도 transaction으로 최종 삭제한다.
 * 실패·응답 누락·SDK 예외는 두 행을 남긴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TimelinePhotoDeleteWorker {

    private static final int MAX_LOGGED_ERROR_CODE_LENGTH = 64;

    private final TimelinePhotoDeleteJobService jobService;
    private final TimelinePhotoDeleteCompletionService completionService;
    private final S3PhotoStorageService s3PhotoStorageService;
    private final TimelinePhotoDeleteWorkerProperties properties;
    private final TimelinePhotoDeleteMetrics metrics;

    @Scheduled(
            cron = "${app.timeline.photo-delete.cron:0 0 3 * * *}",
            zone = "${app.timeline.photo-delete.zone:Asia/Seoul}")
    public void deletePendingPhotoObjects() {
        if (!properties.isWorkerEnabled()) {
            return;
        }

        List<TimelinePhotoDeleteJob> jobs = jobService.findOldest(properties.getBatchSize());
        if (jobs.isEmpty()) {
            return;
        }

        List<String> objectKeys = jobs.stream()
                .map(TimelinePhotoDeleteJob::getObjectKey)
                .toList();
        BatchDeleteResult result;
        Timer.Sample sample = metrics.startBatch();
        try {
            result = s3PhotoStorageService.deleteAll(objectKeys);
        } catch (RuntimeException exception) {
            metrics.recordAttemptFailed(jobs.size());
            log.warn("PHOTO 삭제 batch 호출 실패(job 유지): requested={} exceptionType={}",
                    jobs.size(), exception.getClass().getSimpleName());
            return;
        } finally {
            metrics.recordBatch(sample);
        }

        Set<String> deletedObjectKeys = result.deletedObjectKeys();
        List<TimelinePhotoDeleteJob> succeededJobs = jobs.stream()
                .filter(job -> deletedObjectKeys.contains(job.getObjectKey()))
                .toList();
        int failed = jobs.size() - succeededJobs.size();
        metrics.recordAttemptSuccess(succeededJobs.size());
        metrics.recordAttemptFailed(failed);

        try {
            if (!succeededJobs.isEmpty()) {
                completionService.completeSucceeded(succeededJobs);
            }
        } catch (RuntimeException exception) {
            log.warn("PHOTO 삭제 성공 Item/job 정리 실패(둘 다 유지): succeeded={} exceptionType={}",
                    succeededJobs.size(), exception.getClass().getSimpleName());
            return;
        }

        Map<String, Long> errorCodeCounts = result.errorCodeByObjectKey().values().stream()
                .map(code -> LogSanitizer.sanitize(code, MAX_LOGGED_ERROR_CODE_LENGTH))
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        log.info("PHOTO 삭제 batch 완료: requested={} succeeded={} failed={} unreported={} completed={} errorCodes={}",
                jobs.size(), succeededJobs.size(), failed, result.unreportedObjectKeys().size(), succeededJobs.size(),
                errorCodeCounts);
    }
}
