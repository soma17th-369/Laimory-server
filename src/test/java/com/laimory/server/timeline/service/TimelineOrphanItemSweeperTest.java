package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.laimory.server.timeline.photo.S3PhotoStorageService;
import com.laimory.server.timeline.photo.S3PhotoStorageService.BatchDeleteResult;
import com.laimory.server.timeline.service.TimelineOrphanItemSweepService.PhotoDeletion;
import com.laimory.server.timeline.service.TimelineOrphanItemSweepService.SweepBatchResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import software.amazon.awssdk.core.exception.SdkClientException;

/**
 * 스위퍼가 유효 PHOTO의 S3 삭제를 executor에 넘기고, 삭제가 확인된 key의 Item만 지우는지 검증한다(#551).
 * S3로 넘긴 key 집합은 외부 호출 계약이라 {@code verify}로 확인한다.
 */
@ExtendWith(MockitoExtension.class)
class TimelineOrphanItemSweeperTest {

    private static final String KEY_A = "a".repeat(64) + "/photos/a.jpg";
    private static final String KEY_B = "a".repeat(64) + "/photos/b.jpg";
    private static final String KEY_C = "a".repeat(64) + "/photos/c.jpg";

    @Mock
    private TimelineOrphanItemSweepService sweepService;
    @Mock
    private S3PhotoStorageService s3PhotoStorageService;

    private final TimelineOrphanItemSweeperProperties properties =
            new TimelineOrphanItemSweeperProperties(true, 250, 0, 1, 1);

    @Test
    void deletedPhotoKeysRemoveOnlyTheirItems() {
        stubBatchReturning(new PhotoDeletion(1L, KEY_A), new PhotoDeletion(2L, KEY_B), new PhotoDeletion(3L, KEY_C));
        when(s3PhotoStorageService.deleteAll(List.of(KEY_A, KEY_B, KEY_C))).thenReturn(new BatchDeleteResult(
                Set.of(KEY_A), Map.of(KEY_B, "AccessDenied"), Set.of(KEY_C)));

        sweeperWith(Runnable::run).sweepOrphanItems();

        verify(sweepService).deletePhotoItems(List.of(1L));
    }

    @Test
    void failedAndUnreportedKeysKeepItemsForNextRun() {
        stubBatchReturning(new PhotoDeletion(1L, KEY_A), new PhotoDeletion(2L, KEY_B));
        when(s3PhotoStorageService.deleteAll(List.of(KEY_A, KEY_B))).thenReturn(new BatchDeleteResult(
                Set.of(), Map.of(KEY_A, "InternalError"), Set.of(KEY_B)));

        sweeperWith(Runnable::run).sweepOrphanItems();

        verify(sweepService, never()).deletePhotoItems(anyCollection());
    }

    @Test
    void s3ExceptionKeepsAllPhotoItems() {
        stubBatchReturning(new PhotoDeletion(1L, KEY_A));
        when(s3PhotoStorageService.deleteAll(List.of(KEY_A)))
                .thenThrow(SdkClientException.create("timeout"));

        sweeperWith(Runnable::run).sweepOrphanItems();

        verify(sweepService, never()).deletePhotoItems(anyCollection());
    }

    @Test
    void rejectedSubmissionKeepsItemsAndFinishesRun() {
        stubBatchReturning(new PhotoDeletion(1L, KEY_A));
        TaskExecutor rejecting = task -> {
            throw new TaskRejectedException("busy");
        };

        sweeperWith(rejecting).sweepOrphanItems();

        verifyNoInteractions(s3PhotoStorageService);
        verify(sweepService, never()).deletePhotoItems(anyCollection());
        verify(sweepService).countStaleObservedOrphans(0, 1);
    }

    @Test
    void sweeperReturnsBeforeS3CallCompletes() {
        stubBatchReturning(new PhotoDeletion(1L, KEY_A));
        List<Runnable> submitted = new ArrayList<>();

        sweeperWith(submitted::add).sweepOrphanItems();

        // 제출만 하고 돌아왔다 — S3 호출과 Item 삭제는 아직 일어나지 않았다.
        assertThat(submitted).hasSize(1);
        verifyNoInteractions(s3PhotoStorageService);
        verify(sweepService, never()).deletePhotoItems(anyCollection());
        verify(sweepService).countStaleObservedOrphans(0, 1);
    }

    @Test
    void batchWithoutValidPhotoSubmitsNothing() {
        when(sweepService.observeBatch(0, 1, 250)).thenReturn(List.of(11L));
        when(sweepService.sweepBatch(anyList())).thenReturn(new SweepBatchResult(1, 0, 0, 1, List.of()));
        List<Runnable> submitted = new ArrayList<>();

        sweeperWith(submitted::add).sweepOrphanItems();

        assertThat(submitted).isEmpty();
    }

    private void stubBatchReturning(PhotoDeletion... photoDeletions) {
        when(sweepService.observeBatch(0, 1, 250)).thenReturn(List.of(1L));
        when(sweepService.sweepBatch(List.of(1L)))
                .thenReturn(new SweepBatchResult(1, 0, 0, 0, List.of(photoDeletions)));
    }

    private TimelineOrphanItemSweeper sweeperWith(TaskExecutor executor) {
        return new TimelineOrphanItemSweeper(sweepService, s3PhotoStorageService, properties, executor);
    }
}
