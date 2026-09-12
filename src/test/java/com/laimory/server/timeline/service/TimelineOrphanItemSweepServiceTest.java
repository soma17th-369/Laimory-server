package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.timeline.ItemType;
import com.laimory.server.timeline.entity.TimelineEventItem;
import com.laimory.server.timeline.entity.TimelineItem;
import com.laimory.server.timeline.payload.NotificationPayload;
import com.laimory.server.timeline.payload.PhotoPayload;
import com.laimory.server.timeline.repository.TimelineItemRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class TimelineOrphanItemSweepServiceTest {

    private static final String NAMESPACE_A =
            "1111111111111111111111111111111111111111111111111111111111111111";
    private static final String NAMESPACE_B =
            "2222222222222222222222222222222222222222222222222222222222222222";
    private static final String FILENAME = "0190b2c3-d4e5-7f6a-8b9c-0d1e2f3a4b5c.jpg";
    private static final String KEY_A = NAMESPACE_A + "/photos/" + FILENAME;

    @Mock
    private TimelineItemService timelineItemService;
    @Mock
    private TimelineEventItemService timelineEventItemService;
    @Mock
    private TimelinePhotoDeleteJobService timelinePhotoDeleteJobService;

    private TimelineOrphanItemSweepService service;

    @BeforeEach
    void setUp() {
        service = new TimelineOrphanItemSweepService(timelineItemService, timelineEventItemService,
                timelinePhotoDeleteJobService, new ObjectMapper(), java.time.Clock.systemUTC());
        lenient().when(timelineEventItemService.findByTimelineItemIds(anyCollection()))
                .thenReturn(List.of());
        lenient().when(timelinePhotoDeleteJobService.findItemIdsWithJob(anyCollection()))
                .thenReturn(Set.of());
        lenient().when(timelineItemService.findLiveObjectKeysByFilenames(anyCollection()))
                .thenReturn(Set.of());
        lenient().when(timelineItemService.findUnlinkedPhotoKeysByFilenames(anyCollection()))
                .thenReturn(List.of());
    }

    @Test
    void observationAndStaleThresholdUseClockInSeoul() {
        var clock = java.time.Clock.fixed(java.time.Instant.parse("2026-09-11T18:30:00Z"), java.time.ZoneOffset.UTC);
        service = new TimelineOrphanItemSweepService(timelineItemService, timelineEventItemService,
                timelinePhotoDeleteJobService, new ObjectMapper(), clock);
        scan(notificationItem(11L));
        assertThat(service.observeBatch(1, 2, 250)).containsExactly(11L);
        verify(timelineItemService).markOrphanObserved(List.of(11L), LocalDateTime.of(2026, 9, 12, 3, 30));
        service.countStaleObservedOrphans(1, 2);
        verify(timelineItemService).countStaleObservedOrphans(1, 2, LocalDateTime.of(2026, 9, 9, 3, 30));
    }

    @Test
    void emptySelectionReturnsNoCandidates() {
        when(timelineItemService.findOrphanCandidates(0, 2, 250)).thenReturn(List.of());
        assertThat(service.observeBatch(0, 2, 250)).isEmpty();
        verify(timelineItemService, never()).findByIds(anyCollection());
    }

    @Test
    void nonPhotoOrphanIsDeletedImmediately() {
        TimelineItem item = notificationItem(11L);
        scan(item);
        claim(item);

        var result = service.sweepBatch(service.observeBatch(0, 2, 250));

        assertThat(result.nonPhotoDeleted()).isEqualTo(1);
        verify(timelineItemService).deleteByIds(List.of(11L));
        verify(timelinePhotoDeleteJobService, never()).insertIfAbsent(anyLong(), anyString());
    }

    @Test
    void validPhotoOrphanGetsDeleteJobAndKeepsRow() {
        TimelineItem item = photoItem(12L, FILENAME, "https://cdn.example.net/" + KEY_A);
        scan(item);
        claim(item);
        when(timelineItemService.findUnlinkedPhotoKeysByFilenames(anyCollection()))
                .thenReturn(List.of(row(12L, "https://cdn.example.net/" + KEY_A)));
        when(timelinePhotoDeleteJobService.insertIfAbsent(12L, KEY_A)).thenReturn(true);

        var result = service.sweepBatch(service.observeBatch(0, 2, 250));

        assertThat(result.photoScheduled()).isEqualTo(1);
        verify(timelinePhotoDeleteJobService).insertIfAbsent(12L, KEY_A);
        verify(timelineItemService).deleteByIds(List.of());
    }

    @Test
    void liveItemSharingObjectKeyBlocksJobAndOnlyRowIsDeleted() {
        TimelineItem item = photoItem(13L, FILENAME, "https://cdn.example.net/" + KEY_A);
        scan(item);
        claim(item);
        when(timelineItemService.findLiveObjectKeysByFilenames(anyCollection())).thenReturn(Set.of(KEY_A));

        var result = service.sweepBatch(service.observeBatch(0, 2, 250));

        assertThat(result.keyShared()).isEqualTo(1);
        verify(timelinePhotoDeleteJobService, never()).insertIfAbsent(anyLong(), anyString());
        verify(timelineItemService).deleteByIds(List.of(13L));
    }

    @Test
    void liveItemWithSameFilenameButDifferentNamespaceDoesNotBlockJob() {
        // 다른 subject가 같은 filename을 저장해도 object key는 다르다. coarse filter에는 걸리지만
        // full key 비교에서 갈라져야 한다 — 아니면 남의 Item 때문에 S3 객체가 영구히 남는다.
        TimelineItem item = photoItem(14L, FILENAME, "https://cdn.example.net/" + KEY_A);
        scan(item);
        claim(item);
        when(timelineItemService.findLiveObjectKeysByFilenames(anyCollection()))
                .thenReturn(Set.of(NAMESPACE_B + "/photos/" + FILENAME));
        when(timelineItemService.findUnlinkedPhotoKeysByFilenames(anyCollection()))
                .thenReturn(List.of(row(14L, "https://cdn.example.net/" + KEY_A)));
        when(timelinePhotoDeleteJobService.insertIfAbsent(14L, KEY_A)).thenReturn(true);

        var result = service.sweepBatch(service.observeBatch(0, 2, 250));

        assertThat(result.photoScheduled()).isEqualTo(1);
        assertThat(result.keyShared()).isZero();
    }

    @Test
    void duplicateOrphansShareOneJobOwnedByLowestId() {
        TimelineItem lower = photoItem(15L, FILENAME, "https://cdn.example.net/" + KEY_A);
        TimelineItem higher = photoItem(16L, FILENAME, "https://cdn.example.net/" + KEY_A);
        scan(lower, higher);
        claim(lower, higher);
        when(timelineItemService.findUnlinkedPhotoKeysByFilenames(anyCollection())).thenReturn(List.of(
                row(15L, "https://cdn.example.net/" + KEY_A),
                row(16L, "https://cdn.example.net/" + KEY_A)));
        when(timelinePhotoDeleteJobService.insertIfAbsent(15L, KEY_A)).thenReturn(true);

        var result = service.sweepBatch(service.observeBatch(0, 2, 250));

        assertThat(result.photoScheduled()).isEqualTo(1);
        assertThat(result.keyShared()).isEqualTo(1);
        verify(timelinePhotoDeleteJobService).insertIfAbsent(15L, KEY_A);
        verify(timelinePhotoDeleteJobService, never()).insertIfAbsent(eq(16L), anyString());
        verify(timelineItemService).deleteByIds(List.of(16L));
    }

    @Test
    void unrestorableObjectKeyDropsJobAndDeletesRow() {
        TimelineItem broken = photoItem(17L, FILENAME,
                "https://cdn.example.net/" + NAMESPACE_A.substring(0, 40) + "[REDACTED_CARD]/photos/" + FILENAME);
        scan(broken);
        claim(broken);

        var result = service.sweepBatch(service.observeBatch(0, 2, 250));

        assertThat(result.invalidDeleted()).isEqualTo(1);
        verify(timelinePhotoDeleteJobService, never()).insertIfAbsent(anyLong(), anyString());
        verify(timelineItemService).deleteByIds(List.of(17L));
    }

    @Test
    void duplicateInsertPreservesRowWhenItsJobIsVisible() {
        // insert ignore가 false를 돌려준 이유가 "이 Item의 job이 방금 생겼다"면 행을 지우면 FK 위반이다.
        // 이 mock은 조회 결과별 분기만 검증한다. RR snapshot 뒤 동시 생성 job의 가시성을 보장하지 않는다.
        TimelineItem item = photoItem(18L, FILENAME, "https://cdn.example.net/" + KEY_A);
        scan(item);
        claim(item);
        when(timelineItemService.findUnlinkedPhotoKeysByFilenames(anyCollection()))
                .thenReturn(List.of(row(18L, "https://cdn.example.net/" + KEY_A)));
        when(timelinePhotoDeleteJobService.insertIfAbsent(18L, KEY_A)).thenReturn(false);
        when(timelinePhotoDeleteJobService.findItemIdsWithJob(anyCollection()))
                .thenReturn(Set.of(), Set.of(18L));

        var result = service.sweepBatch(service.observeBatch(0, 2, 250));

        assertThat(result.photoAlreadyJob()).isEqualTo(1);
        assertThat(result.keyShared()).isZero();
        verify(timelineItemService).deleteByIds(List.of());
    }

    @Test
    void objectKeyTakenByAnotherItemDeletesRow() {
        TimelineItem item = photoItem(19L, FILENAME, "https://cdn.example.net/" + KEY_A);
        scan(item);
        claim(item);
        when(timelineItemService.findUnlinkedPhotoKeysByFilenames(anyCollection()))
                .thenReturn(List.of(row(19L, "https://cdn.example.net/" + KEY_A)));
        when(timelinePhotoDeleteJobService.insertIfAbsent(19L, KEY_A)).thenReturn(false);
        when(timelinePhotoDeleteJobService.findItemIdsWithJob(List.of(19L))).thenReturn(Set.of());

        var result = service.sweepBatch(service.observeBatch(0, 2, 250));

        assertThat(result.keyShared()).isEqualTo(1);
        verify(timelineItemService).deleteByIds(List.of(19L));
    }

    @Test
    void revalidationDropsItemsThatGainedJobOrJunctionAfterScan() {
        TimelineItem gainedJob = photoItem(20L, FILENAME, "https://cdn.example.net/" + KEY_A);
        TimelineItem gainedJunction = notificationItem(21L);
        scan(gainedJob, gainedJunction);
        claim(gainedJob, gainedJunction);
        when(timelinePhotoDeleteJobService.findItemIdsWithJob(List.of(20L, 21L))).thenReturn(Set.of(20L));
        when(timelineEventItemService.findByTimelineItemIds(List.of(20L, 21L)))
                .thenReturn(List.of(TimelineEventItem.of(99L, 21L)));

        var result = service.sweepBatch(service.observeBatch(0, 2, 250));

        assertThat(result.revalidationDropped()).isEqualTo(2);
        verify(timelineItemService).deleteByIds(List.of());
        verify(timelinePhotoDeleteJobService, never()).insertIfAbsent(anyLong(), anyString());
    }

    @Test
    void rowsRemovedAfterObservationAreRevalidationDrops() {
        scan(notificationItem(30L), notificationItem(31L));
        when(timelineItemService.findByIds(List.of(30L, 31L))).thenReturn(List.of());
        var result = service.sweepBatch(service.observeBatch(0, 2, 250));
        assertThat(result.selected()).isEqualTo(2);
        assertThat(result.revalidationDropped()).isEqualTo(2);
    }

    private void scan(TimelineItem... items) {
        when(timelineItemService.findOrphanCandidates(anyInt(), anyInt(), anyInt()))
                .thenReturn(List.of(items));
    }

    private void claim(TimelineItem... items) {
        when(timelineItemService.findByIds(anyCollection())).thenReturn(List.of(items));
    }

    private TimelineItem photoItem(long id, String filename, String photoUrl) {
        PhotoPayload payload = new PhotoPayload(filename, "content://x", null, null, null, null, null, photoUrl);
        TimelineItem item = TimelineItem.of(ItemType.PHOTO, "raw-" + id,
                LocalDateTime.of(2026, 6, 17, 9, 0), null, new ObjectMapper().valueToTree(payload));
        ReflectionTestUtils.setField(item, "timelineItemId", id);
        return item;
    }

    private TimelineItem notificationItem(long id) {
        TimelineItem item = TimelineItem.of(ItemType.NOTIFICATION, "raw-" + id,
                LocalDateTime.of(2026, 6, 17, 9, 0), null,
                new ObjectMapper().valueToTree(new NotificationPayload("app", "title", "text")));
        ReflectionTestUtils.setField(item, "timelineItemId", id);
        return item;
    }

    private TimelineItemRepository.OrphanPhotoKeyRow row(long id, String photoUrl) {
        return new TimelineItemRepository.OrphanPhotoKeyRow() {
            @Override
            public Long getTimelineItemId() {
                return id;
            }

            @Override
            public String getPhotoUrl() {
                return photoUrl;
            }
        };
    }
}
