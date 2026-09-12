package com.laimory.server.timeline.service;

import static com.laimory.server.testsupport.TestSubjects.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.laimory.server.timeline.ItemType;
import com.laimory.server.timeline.entity.TimelineDraftSourceItem;
import com.laimory.server.timeline.payload.PhotoPayload;
import com.laimory.server.timeline.payload.StayPayload;
import com.laimory.server.timeline.photo.PhotoObjectKeys;
import com.laimory.server.timeline.photo.S3PhotoStorageService;
import com.laimory.server.timeline.photo.S3PhotoStorageService.BatchDeleteResult;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.util.ReflectionTestUtils;

/** cleanup worker의 cutoff, S3 batch, 부분 실패와 malformed 정책을 인프라 없이 검증한다. */
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class TimelineDraftCleanupSchedulerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Clock FIXED = Clock.fixed(
            Instant.parse("2026-06-22T03:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate DATE = LocalDate.of(2026, 6, 1);
    private static final UUID SUBJECT_ID = id(7L);
    private static final String FILENAME = "0190b2c3-d4e5-7f6a-8b9c-0d1e2f3a4b5c.jpg";

    @Mock
    private TimelineDraftSourceItemService timelineDraftSourceItemService;

    @Mock
    private S3PhotoStorageService s3PhotoStorageService;

    @Mock
    private TimelineDraftCleanupWorkerProperties properties;

    @BeforeEach
    void setUp() {
        lenient().when(properties.isWorkerEnabled()).thenReturn(true);
        lenient().when(properties.getRetentionDays()).thenReturn(7L);
        lenient().when(properties.getBatchSize()).thenReturn(250);
        lenient().when(properties.getWorkerCount()).thenReturn(1);
        lenient().when(properties.getTotalWorkerCount()).thenReturn(2);
    }

    @Test
    void fullBatchIsSelectedOnlyOncePerSlotAndCanRetryNextRun() {
        var configured = new TimelineDraftCleanupWorkerProperties(true, 7, 1, 1, 2, 2);
        var worker = new TimelineDraftCleanupScheduler(timelineDraftSourceItemService, s3PhotoStorageService,
                new ObjectMapper(), FIXED, configured, Runnable::run);
        when(timelineDraftSourceItemService.findExpired(any(), org.mockito.ArgumentMatchers.eq(2),
                org.mockito.ArgumentMatchers.eq(4), org.mockito.ArgumentMatchers.eq(1)))
                .thenReturn(List.of(stayRow(30L)));
        when(timelineDraftSourceItemService.findExpired(any(), org.mockito.ArgumentMatchers.eq(3),
                org.mockito.ArgumentMatchers.eq(4), org.mockito.ArgumentMatchers.eq(1)))
                .thenReturn(List.of(stayRow(31L)));
        worker.cleanupExpiredDrafts();
        verify(timelineDraftSourceItemService).findExpired(any(), org.mockito.ArgumentMatchers.eq(2),
                org.mockito.ArgumentMatchers.eq(4), org.mockito.ArgumentMatchers.eq(1));
        verify(timelineDraftSourceItemService).findExpired(any(), org.mockito.ArgumentMatchers.eq(3),
                org.mockito.ArgumentMatchers.eq(4), org.mockito.ArgumentMatchers.eq(1));
        worker.cleanupExpiredDrafts();
        verify(timelineDraftSourceItemService, org.mockito.Mockito.times(2)).findExpired(any(),
                org.mockito.ArgumentMatchers.eq(2), org.mockito.ArgumentMatchers.eq(4), org.mockito.ArgumentMatchers.eq(1));
    }

    @Test
    void cleanup_claimsRowsUsingApplicationClockCutoff() {
        scheduler().cleanupExpiredDrafts();

        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(timelineDraftSourceItemService).findExpired(cutoff.capture(), org.mockito.ArgumentMatchers.eq(0), org.mockito.ArgumentMatchers.eq(2), org.mockito.ArgumentMatchers.eq(250));
        assertThat(cutoff.getValue()).isEqualTo(LocalDateTime.of(2026, 6, 15, 3, 0));
    }

    @Test
    void cleanup_photo_usesBatchDeleteThenBulkDeletesSuccessfulRow() {
        TimelineDraftSourceItem photo = photoRow(10L, FILENAME);
        when(timelineDraftSourceItemService.findExpired(any(), org.mockito.ArgumentMatchers.eq(0), org.mockito.ArgumentMatchers.eq(2), org.mockito.ArgumentMatchers.eq(250)))
                .thenReturn(List.of(photo));
        String objectKey = PhotoObjectKeys.subjectFullKey(FILENAME, SUBJECT_ID);
        when(s3PhotoStorageService.deleteAll(List.of(objectKey)))
                .thenReturn(result(Set.of(objectKey), Map.of(), Set.of()));
        when(timelineDraftSourceItemService.deleteExpired(Set.of(10L))).thenReturn(1);

        scheduler().cleanupExpiredDrafts();

        verify(s3PhotoStorageService).deleteAll(List.of(objectKey));
        verify(timelineDraftSourceItemService).deleteExpired(Set.of(10L));
    }

    @Test
    void cleanup_partialS3Result_keepsFailedPhotoAndLogsRunSummary(CapturedOutput output) {
        TimelineDraftSourceItem deleted = photoRow(20L, FILENAME);
        TimelineDraftSourceItem failed = photoRow(21L, "failed.jpg");
        TimelineDraftSourceItem stay = stayRow(22L);
        when(timelineDraftSourceItemService.findExpired(any(), org.mockito.ArgumentMatchers.eq(0), org.mockito.ArgumentMatchers.eq(2), org.mockito.ArgumentMatchers.eq(250)))
                .thenReturn(List.of(deleted, failed, stay));
        String deletedKey = PhotoObjectKeys.subjectFullKey(FILENAME, SUBJECT_ID);
        String failedKey = PhotoObjectKeys.subjectFullKey("failed.jpg", SUBJECT_ID);
        when(s3PhotoStorageService.deleteAll(List.of(deletedKey, failedKey)))
                .thenReturn(result(Set.of(deletedKey), Map.of(failedKey, "InternalError"), Set.of()));
        when(timelineDraftSourceItemService.deleteExpired(Set.of(22L, 20L))).thenReturn(2);

        scheduler().cleanupExpiredDrafts();

        verify(timelineDraftSourceItemService).deleteExpired(Set.of(22L, 20L));
        assertThat(output)
                .contains("draft cleanup batch 완료: selected=3, succeeded=2, failed=1, deleted=2")
                .contains("photoDeleteRequested=2, photoDeleteSucceeded=1, photoDeleteFailed=1")
                .contains("draft cleanup run 완료: batches=1, selected=3, succeeded=2, failed=1, deleted=2")
                .contains("workerErrors=0, durationMs=");
    }

    @Test
    void cleanup_s3FailureStillDeletesNonPhotoButKeepsValidPhoto() {
        TimelineDraftSourceItem photo = photoRow(30L, FILENAME);
        TimelineDraftSourceItem stay = stayRow(31L);
        when(timelineDraftSourceItemService.findExpired(any(), org.mockito.ArgumentMatchers.eq(0), org.mockito.ArgumentMatchers.eq(2), org.mockito.ArgumentMatchers.eq(250)))
                .thenReturn(List.of(photo, stay));
        String objectKey = PhotoObjectKeys.subjectFullKey(FILENAME, SUBJECT_ID);
        when(s3PhotoStorageService.deleteAll(List.of(objectKey))).thenThrow(new RuntimeException("s3 down"));
        when(timelineDraftSourceItemService.deleteExpired(Set.of(31L))).thenReturn(1);

        scheduler().cleanupExpiredDrafts();

        verify(timelineDraftSourceItemService).deleteExpired(Set.of(31L));
    }

    @Test
    void cleanup_malformedOrBlankPhotosPreserveExistingOrphanPolicy() {
        List<TimelineDraftSourceItem> rows = List.of(
                photoRow(40L, ""),
                photoRow(41L, NullNode.getInstance()),
                photoRow(42L, MAPPER.createArrayNode()));
        when(timelineDraftSourceItemService.findExpired(any(), org.mockito.ArgumentMatchers.eq(0), org.mockito.ArgumentMatchers.eq(2), org.mockito.ArgumentMatchers.eq(250)))
                .thenReturn(rows);
        when(timelineDraftSourceItemService.deleteExpired(Set.of(40L, 41L, 42L))).thenReturn(3);

        scheduler().cleanupExpiredDrafts();

        verifyNoInteractions(s3PhotoStorageService);
        verify(timelineDraftSourceItemService).deleteExpired(Set.of(40L, 41L, 42L));
    }

    @Test
    void disabledOrEmptyQueueDoesNotCallS3() {
        when(properties.isWorkerEnabled()).thenReturn(false);
        scheduler().cleanupExpiredDrafts();

        verifyNoInteractions(s3PhotoStorageService);
        verify(timelineDraftSourceItemService, never()).findExpired(any(), org.mockito.ArgumentMatchers.eq(0), org.mockito.ArgumentMatchers.eq(2), org.mockito.ArgumentMatchers.anyInt());
    }

    private TimelineDraftCleanupScheduler scheduler() {
        return new TimelineDraftCleanupScheduler(
                timelineDraftSourceItemService,
                s3PhotoStorageService,
                MAPPER,
                FIXED,
                properties,
                Runnable::run);
    }

    private TimelineDraftSourceItem photoRow(long rowId, String filename) {
        return photoRow(rowId, MAPPER.valueToTree(new PhotoPayload(
                filename, "content://x", 1.0, 2.0, null,
                null, null,
                "https://cdn.example/hash/photos/" + filename)));
    }

    private TimelineDraftSourceItem photoRow(long rowId, JsonNode payload) {
        TimelineDraftSourceItem row = TimelineDraftSourceItem.of(
                "task-" + rowId, SUBJECT_ID, ItemType.PHOTO, "r" + rowId,
                DATE.atTime(9, 0), null, payload);
        ReflectionTestUtils.setField(row, "timelineDraftSourceItemId", rowId);
        return row;
    }

    private TimelineDraftSourceItem stayRow(long rowId) {
        TimelineDraftSourceItem row = TimelineDraftSourceItem.of(
                "task-" + rowId, SUBJECT_ID, ItemType.STAY, "r" + rowId,
                DATE.atTime(9, 0), null,
                MAPPER.valueToTree(new StayPayload(3.0, 4.0, null, null, null)));
        ReflectionTestUtils.setField(row, "timelineDraftSourceItemId", rowId);
        return row;
    }

    private BatchDeleteResult result(
            Set<String> deleted,
            Map<String, String> errors,
            Set<String> unreported) {
        return new BatchDeleteResult(deleted, errors, unreported);
    }
}
