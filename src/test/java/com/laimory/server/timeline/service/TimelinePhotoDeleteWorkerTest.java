package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.laimory.server.config.TimelineWorkerExecutorConfig;
import com.laimory.server.timeline.entity.TimelinePhotoDeleteJob;
import com.laimory.server.timeline.photo.S3PhotoStorageService;
import com.laimory.server.timeline.photo.S3PhotoStorageService.BatchDeleteResult;
import com.laimory.server.timeline.service.TimelinePhotoDeleteJobService.ValidationResult;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import software.amazon.awssdk.core.exception.SdkClientException;

@ExtendWith(MockitoExtension.class)
class TimelinePhotoDeleteWorkerTest {

    @Mock
    private TimelinePhotoDeleteJobService jobService;

    @Mock
    private S3PhotoStorageService s3PhotoStorageService;

    @Mock
    private TimelinePhotoDeleteWorkerProperties properties;

    private TimelinePhotoDeleteWorker worker;

    @BeforeEach
    void setUp() {
        worker = new TimelinePhotoDeleteWorker(
                jobService, s3PhotoStorageService, properties, Runnable::run);
        lenient().when(properties.getConcurrency()).thenReturn(1);
        lenient().when(properties.getMaxBatchesPerRun()).thenReturn(1);
        lenient().when(properties.getMaxRunDuration()).thenReturn(Duration.ofSeconds(60));
        lenient().when(jobService.completeSucceeded(anyList()))
                .thenAnswer(invocation -> invocation.<List<?>>getArgument(0).size());
        lenient().when(jobService.retainOrphanJobs(anyList()))
                .thenAnswer(invocation -> new ValidationResult(invocation.getArgument(0), 0));
    }

    @Test
    void disabledWorkerIsNoOp() {
        when(properties.isWorkerEnabled()).thenReturn(false);

        worker.deletePendingPhotoObjects();

        verifyNoInteractions(jobService, s3PhotoStorageService);
    }

    @Test
    void emptyQueueSkipsS3() {
        when(properties.isWorkerEnabled()).thenReturn(true);
        when(properties.getBatchSize()).thenReturn(250);
        when(jobService.claimEligible(250)).thenReturn(List.of());

        worker.deletePendingPhotoObjects();

        verifyNoInteractions(s3PhotoStorageService);
        verify(jobService, never()).completeSucceeded(anyList());
    }

    @Test
    void expiredJobsAreAlertedWithCountOnlyAndNoIdentifiers() {
        when(properties.isWorkerEnabled()).thenReturn(true);
        when(properties.getBatchSize()).thenReturn(250);
        when(jobService.countExpired()).thenReturn(3L);
        when(jobService.claimEligible(250)).thenReturn(List.of());

        List<ILoggingEvent> events = captureWorkerLogs(worker::deletePendingPhotoObjects);

        List<ILoggingEvent> errors = events.stream()
                .filter(event -> event.getLevel() == Level.ERROR)
                .toList();
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).getFormattedMessage())
                .contains("expiredCount=3")
                .doesNotContain("hash/photos");
    }

    @Test
    void zeroExpiredJobsEmitNoErrorLog() {
        when(properties.isWorkerEnabled()).thenReturn(true);
        when(properties.getBatchSize()).thenReturn(250);
        when(jobService.countExpired()).thenReturn(0L);
        when(jobService.claimEligible(250)).thenReturn(List.of());

        List<ILoggingEvent> events = captureWorkerLogs(worker::deletePendingPhotoObjects);

        assertThat(events).noneMatch(event -> event.getLevel() == Level.ERROR);
    }

    @Test
    void expiredCountFailureLogsWarnAndDoesNotBlockClaims() {
        TimelinePhotoDeleteJob job = job(91L, "hash/photos/deleted.jpg");
        enableWithJobs(job);
        when(jobService.countExpired()).thenThrow(new IllegalStateException("db unavailable"));
        when(s3PhotoStorageService.deleteAll(List.of("hash/photos/deleted.jpg")))
                .thenReturn(result(Set.of("hash/photos/deleted.jpg"), Map.of(), Set.of()));

        List<ILoggingEvent> events = captureWorkerLogs(worker::deletePendingPhotoObjects);

        verify(jobService).completeSucceeded(List.of(job));
        assertThat(events).noneMatch(event -> event.getLevel() == Level.ERROR);
        assertThat(events).anyMatch(event -> event.getLevel() == Level.WARN
                && event.getFormattedMessage().contains("만료 job count 조회 실패"));
    }

    @Test
    void relinkedJobsAreCancelledAndExcludedBeforeS3Delete() {
        TimelinePhotoDeleteJob relinked = job(15L, "hash/photos/relinked.jpg");
        enableWithJobs(relinked);
        when(jobService.retainOrphanJobs(List.of(relinked)))
                .thenReturn(new ValidationResult(List.of(), 1));

        worker.deletePendingPhotoObjects();

        verify(jobService).retainOrphanJobs(List.of(relinked));
        verifyNoInteractions(s3PhotoStorageService);
        verify(jobService, never()).completeSucceeded(anyList());
    }

    @Test
    void orphanValidationFailureKeepsJobsAndSkipsS3Delete() {
        TimelinePhotoDeleteJob job = job(16L, "hash/photos/validation-failed.jpg");
        enableWithJobs(job);
        when(jobService.retainOrphanJobs(List.of(job)))
                .thenThrow(new IllegalStateException("db unavailable"));

        worker.deletePendingPhotoObjects();

        verifyNoInteractions(s3PhotoStorageService);
        verify(jobService, never()).completeSucceeded(anyList());
        verify(jobService).markPendingForRetry(List.of(job));
    }

    @Test
    void fullSuccessDeletesAllSucceededRowsAndRecordsResults() {
        TimelinePhotoDeleteJob first = job(11L, "hash/photos/first.jpg");
        TimelinePhotoDeleteJob second = job(12L, "hash/photos/second.jpg");
        enableWithJobs(first, second);
        when(s3PhotoStorageService.deleteAll(List.of(
                        "hash/photos/first.jpg", "hash/photos/second.jpg")))
                .thenReturn(result(
                        Set.of("hash/photos/first.jpg", "hash/photos/second.jpg"),
                        Map.of(),
                        Set.of()));
        worker.deletePendingPhotoObjects();

        verify(jobService).completeSucceeded(List.of(first, second));
    }

    @Test
    void partialResultDeletesOnlyDeletedRowsAndCountsErrorAndUnreportedAsFailed() {
        TimelinePhotoDeleteJob deleted = job(21L, "hash/photos/deleted.jpg");
        TimelinePhotoDeleteJob error = job(22L, "hash/photos/error.jpg");
        TimelinePhotoDeleteJob unreported = job(23L, "hash/photos/unreported.jpg");
        enableWithJobs(deleted, error, unreported);
        when(s3PhotoStorageService.deleteAll(List.of(
                        "hash/photos/deleted.jpg",
                        "hash/photos/error.jpg",
                        "hash/photos/unreported.jpg")))
                .thenReturn(result(
                        Set.of("hash/photos/deleted.jpg"),
                        Map.of("hash/photos/error.jpg", "InternalError"),
                        Set.of("hash/photos/unreported.jpg")));
        worker.deletePendingPhotoObjects();

        verify(jobService).completeSucceeded(List.of(deleted));
        verify(jobService).markPendingForRetry(List.of(error, unreported));
    }

    @Test
    void explicitS3ErrorLeavesJobAndRecordsFailure() {
        TimelinePhotoDeleteJob error = job(31L, "hash/photos/error.jpg");
        enableWithJobs(error);
        when(s3PhotoStorageService.deleteAll(List.of("hash/photos/error.jpg")))
                .thenReturn(result(
                        Set.of(),
                        Map.of("hash/photos/error.jpg", "AccessDenied"),
                        Set.of()));

        worker.deletePendingPhotoObjects();

        verify(jobService, never()).completeSucceeded(anyList());
        verify(jobService).markPendingForRetry(List.of(error));
    }

    @Test
    void unreportedS3KeyLeavesJobAndRecordsFailure() {
        TimelinePhotoDeleteJob unreported = job(41L, "hash/photos/unreported.jpg");
        enableWithJobs(unreported);
        when(s3PhotoStorageService.deleteAll(List.of("hash/photos/unreported.jpg")))
                .thenReturn(result(
                        Set.of(),
                        Map.of(),
                        Set.of("hash/photos/unreported.jpg")));

        worker.deletePendingPhotoObjects();

        verify(jobService, never()).completeSucceeded(anyList());
        verify(jobService).markPendingForRetry(List.of(unreported));
    }

    @Test
    void sdkExceptionLeavesAllJobsForRetry() {
        TimelinePhotoDeleteJob first = job(51L, "hash/photos/first.jpg");
        TimelinePhotoDeleteJob second = job(52L, "hash/photos/second.jpg");
        enableWithJobs(first, second);
        when(s3PhotoStorageService.deleteAll(List.of(
                        "hash/photos/first.jpg", "hash/photos/second.jpg")))
                .thenThrow(SdkClientException.create("connect timeout"));

        worker.deletePendingPhotoObjects();

        verify(jobService, never()).completeSucceeded(anyList());
        verify(jobService).markPendingForRetry(List.of(first, second));
    }

    @Test
    void completionFailureLeavesSucceededItemAndJobForRetry() {
        TimelinePhotoDeleteJob job = job(61L, "hash/photos/deleted.jpg");
        enableWithJobs(job);
        when(s3PhotoStorageService.deleteAll(List.of("hash/photos/deleted.jpg")))
                .thenReturn(result(Set.of("hash/photos/deleted.jpg"), Map.of(), Set.of()));
        doThrow(new IllegalStateException("db unavailable"))
                .when(jobService).completeSucceeded(List.of(job));

        assertThatCode(worker::deletePendingPhotoObjects).doesNotThrowAnyException();

        verify(jobService).completeSucceeded(List.of(job));
        verify(jobService).markPendingForRetry(List.of(job));
    }

    @Test
    void allWorkerSlotsShareProcessWideBatchBudget() {
        TimelinePhotoDeleteJob job = job(71L, "hash/photos/deleted.jpg");
        when(properties.isWorkerEnabled()).thenReturn(true);
        when(properties.getConcurrency()).thenReturn(2);
        when(properties.getMaxBatchesPerRun()).thenReturn(4);
        when(properties.getBatchSize()).thenReturn(250);
        when(jobService.claimEligible(250)).thenReturn(List.of(job));
        when(s3PhotoStorageService.deleteAll(List.of("hash/photos/deleted.jpg")))
                .thenReturn(result(Set.of("hash/photos/deleted.jpg"), Map.of(), Set.of()));

        worker.deletePendingPhotoObjects();

        verify(jobService, org.mockito.Mockito.times(4)).claimEligible(250);
        verify(s3PhotoStorageService, org.mockito.Mockito.times(4))
                .deleteAll(List.of("hash/photos/deleted.jpg"));
    }

    @Test
    void concurrencyTwoUsesConfiguredExecutorToProcessDifferentBatchesInParallel() throws Exception {
        TimelinePhotoDeleteJob first = job(81L, "hash/photos/first.jpg");
        TimelinePhotoDeleteJob second = job(82L, "hash/photos/second.jpg");
        TimelinePhotoDeleteWorkerProperties concurrentProperties =
                new TimelinePhotoDeleteWorkerProperties(true, 250, 2, 2, Duration.ofSeconds(60));
        ThreadPoolTaskExecutor executor = new TimelineWorkerExecutorConfig()
                .timelinePhotoDeleteWorkerExecutor(concurrentProperties);
        executor.initialize();

        CountDownLatch bothSlotsClaiming = new CountDownLatch(2);
        AtomicInteger claimOrder = new AtomicInteger();
        Set<String> claimThreadNames = ConcurrentHashMap.newKeySet();
        when(jobService.claimEligible(250)).thenAnswer(invocation -> {
            int order = claimOrder.getAndIncrement();
            claimThreadNames.add(Thread.currentThread().getName());
            bothSlotsClaiming.countDown();
            if (!bothSlotsClaiming.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("both worker slots did not start");
            }
            return order == 0 ? List.of(first) : List.of(second);
        });
        when(s3PhotoStorageService.deleteAll(anyList())).thenAnswer(invocation -> {
            List<String> keys = invocation.getArgument(0);
            return result(Set.copyOf(keys), Map.of(), Set.of());
        });
        worker = new TimelinePhotoDeleteWorker(
                jobService,
                s3PhotoStorageService,
                concurrentProperties,
                executor);

        try {
            worker.deletePendingPhotoObjects();

            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                verify(jobService).completeSucceeded(List.of(first));
                verify(jobService).completeSucceeded(List.of(second));
                assertThat(claimThreadNames)
                        .hasSize(2)
                        .allMatch(name -> name.startsWith("photo-delete-"));
            });
        } finally {
            executor.shutdown();
        }
    }

    private List<ILoggingEvent> captureWorkerLogs(Runnable run) {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        Logger logger = (Logger) LoggerFactory.getLogger(TimelinePhotoDeleteWorker.class);
        appender.start();
        logger.addAppender(appender);
        try {
            run.run();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
        return List.copyOf(appender.list);
    }

    private void enableWithJobs(TimelinePhotoDeleteJob... jobs) {
        when(properties.isWorkerEnabled()).thenReturn(true);
        when(properties.getBatchSize()).thenReturn(250);
        when(jobService.claimEligible(250)).thenReturn(List.of(jobs));
    }

    private TimelinePhotoDeleteJob job(long id, String objectKey) {
        TimelinePhotoDeleteJob job = org.mockito.Mockito.mock(TimelinePhotoDeleteJob.class);
        lenient().when(job.getObjectKey()).thenReturn(objectKey);
        lenient().when(job.getTimelinePhotoDeleteJobId()).thenReturn(id);
        return job;
    }

    private BatchDeleteResult result(
            Set<String> deleted,
            Map<String, String> errors,
            Set<String> unreported) {
        return new BatchDeleteResult(deleted, errors, unreported);
    }
}
