package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.timeline.ItemType;
import com.laimory.server.timeline.entity.TimelineDraftSourceItem;
import com.laimory.server.timeline.payload.LocationPayload;
import com.laimory.server.timeline.payload.PhotoPayload;
import com.laimory.server.timeline.photo.PhotoObjectKeys;
import com.laimory.server.timeline.photo.S3PhotoStorageService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * cleanup 스케줄러 단위 검증. cutoff 결정론(고정 Clock+retentionDays) + 행별 S3 삭제→행 삭제,
 * S3 실패 시 행 보존(재시도)을 검증한다. 인프라 0(S3 어댑터는 Mockito mock).
 */
@ExtendWith(MockitoExtension.class)
class TimelineDraftCleanupSchedulerTest {

    @Mock
    private TimelineDraftSourceItemService timelineDraftSourceItemService;
    @Mock
    private TimelineDraftEventSuggestionService timelineDraftEventSuggestionService;
    @Mock
    private S3PhotoStorageService s3PhotoStorageService;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-06-22T03:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate DATE = LocalDate.of(2026, 6, 1);
    private static final long USER_ID = 7L;
    private static final String FILENAME = "0190b2c3-d4e5-7f6a-8b9c-0d1e2f3a4b5c.jpg";

    private TimelineDraftCleanupScheduler scheduler(long retentionDays) {
        TimelineDraftCleanupScheduler scheduler = new TimelineDraftCleanupScheduler(
                timelineDraftSourceItemService, timelineDraftEventSuggestionService, s3PhotoStorageService, MAPPER, FIXED);
        ReflectionTestUtils.setField(scheduler, "retentionDays", retentionDays);
        return scheduler;
    }

    private TimelineDraftSourceItem photoRow(long id, String filename) {
        TimelineDraftSourceItem row = TimelineDraftSourceItem.of("task-" + id, USER_ID, ItemType.PHOTO, "r" + id, DATE.atTime(9, 0), null,
                MAPPER.valueToTree(new PhotoPayload(filename, "content://x", 1.0, 2.0, null)));
        ReflectionTestUtils.setField(row, "timelineDraftSourceItemId", id);
        return row;
    }

    private TimelineDraftSourceItem locationRow(long id) {
        TimelineDraftSourceItem row = TimelineDraftSourceItem.of("task-" + id, USER_ID, ItemType.LOCATION, "r" + id, DATE.atTime(9, 0), null,
                MAPPER.valueToTree(new LocationPayload(3.0, 4.0, null, null, null)));
        ReflectionTestUtils.setField(row, "timelineDraftSourceItemId", id);
        return row;
    }

    // --- cutoff 결정론 ---

    @Test
    void cleanup_queriesRowsOlderThanRetention() {
        scheduler(7L).cleanupExpiredDrafts();

        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(timelineDraftSourceItemService).findCreatedBefore(cutoff.capture());
        assertThat(cutoff.getValue())
                .isEqualTo(LocalDateTime.now(FIXED).minusDays(7))
                .isEqualTo(LocalDateTime.of(2026, 6, 15, 3, 0));
    }

    @Test
    void cleanup_cutoffRespectsConfiguredRetention() {
        scheduler(30L).cleanupExpiredDrafts();

        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(timelineDraftSourceItemService).findCreatedBefore(cutoff.capture());
        assertThat(cutoff.getValue())
                .isEqualTo(LocalDateTime.now(FIXED).minusDays(30))
                .isEqualTo(LocalDateTime.of(2026, 5, 23, 3, 0));
    }

    // --- 행별 S3 삭제 → 행 삭제 ---

    @Test
    void cleanup_photo_deletesS3ObjectThenRow() {
        when(timelineDraftSourceItemService.findCreatedBefore(any()))
                .thenReturn(List.of(photoRow(10L, FILENAME)));

        scheduler(7L).cleanupExpiredDrafts();

        // filename → fullKey({sha256hex(userId)}/photos/{filename}) 복원해 S3 삭제.
        verify(s3PhotoStorageService).delete(PhotoObjectKeys.fullKey(FILENAME, USER_ID));
        verify(timelineDraftSourceItemService).deleteById(10L);
    }

    @Test
    void cleanup_nonPhoto_deletesRowWithoutS3() {
        when(timelineDraftSourceItemService.findCreatedBefore(any()))
                .thenReturn(List.of(locationRow(11L)));

        scheduler(7L).cleanupExpiredDrafts();

        verify(s3PhotoStorageService, never()).delete(any());
        verify(timelineDraftSourceItemService).deleteById(11L);
    }

    @Test
    void cleanup_s3DeleteFails_keepsRowAndContinuesOthers() {
        TimelineDraftSourceItem failing = photoRow(20L, FILENAME);
        TimelineDraftSourceItem ok = locationRow(21L);
        when(timelineDraftSourceItemService.findCreatedBefore(any()))
                .thenReturn(List.of(failing, ok));
        doThrow(new RuntimeException("s3 down"))
                .when(s3PhotoStorageService).delete(PhotoObjectKeys.fullKey(FILENAME, USER_ID));

        scheduler(7L).cleanupExpiredDrafts();

        // 실패한 PHOTO 행은 삭제하지 않고(다음 실행 재시도), 나머지 행은 계속 정리한다.
        verify(timelineDraftSourceItemService, never()).deleteById(20L);
        verify(timelineDraftSourceItemService).deleteById(21L);
    }

    @Test
    void cleanup_photoWithBlankFilename_skipsS3ButStillDeletesRow() {
        when(timelineDraftSourceItemService.findCreatedBefore(any()))
                .thenReturn(List.of(photoRow(30L, "")));

        scheduler(7L).cleanupExpiredDrafts();

        // filename을 못 만들면 S3 삭제는 건너뛰되 만료 행은 정리한다(객체는 orphan 인정).
        verify(s3PhotoStorageService, never()).delete(any());
        verify(timelineDraftSourceItemService).deleteById(30L);
    }

    @Test
    void cleanup_emptyExpired_doesNothing() {
        when(timelineDraftSourceItemService.findCreatedBefore(any())).thenReturn(List.of());

        scheduler(7L).cleanupExpiredDrafts();

        verify(s3PhotoStorageService, never()).delete(any());
        verify(timelineDraftSourceItemService, never()).deleteById(anyLong());
    }

    // --- event suggestion 정리(S3 없음 → 단일 bulk delete) ---

    @Test
    void cleanup_deletesExpiredEventSuggestions_viaBulkDeleteWithCutoff() {
        scheduler(7L).cleanupExpiredDrafts();

        // 행별 select+delete가 아니라 cutoff 기준 단일 bulk delete를 호출한다.
        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(timelineDraftEventSuggestionService).deleteCreatedBefore(cutoff.capture());
        assertThat(cutoff.getValue()).isEqualTo(LocalDateTime.now(FIXED).minusDays(7));
    }

    // --- 불변식 fail-fast 가드 (retention ≫ PROCESSING_TTL 1h) ---

    @Test
    void validate_rejectsZeroOrNegativeRetention() {
        for (long bad : new long[] {0L, -1L}) {
            TimelineDraftCleanupScheduler scheduler = scheduler(bad);

            assertThatThrownBy(scheduler::validateRetentionDays)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("retention-days");
        }
    }

    @Test
    void validate_acceptsMinimumRetention() {
        assertThatCode(scheduler(1L)::validateRetentionDays).doesNotThrowAnyException();
    }
}
