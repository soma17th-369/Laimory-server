package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.laimory.server.timeline.ItemType;
import com.laimory.server.timeline.entity.TimelineItem;
import com.laimory.server.timeline.payload.NotificationPayload;
import com.laimory.server.timeline.payload.PhotoPayload;
import com.laimory.server.timeline.service.TimelineOrphanItemSweepService.PhotoDeletion;
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
    private static final String FILENAME = "0190b2c3-d4e5-7f6a-8b9c-0d1e2f3a4b5c.jpg";
    private static final String KEY_A = NAMESPACE_A + "/photos/" + FILENAME;

    @Mock
    private TimelineItemService timelineItemService;
    @Mock
    private TimelineEventItemService timelineEventItemService;

    private TimelineOrphanItemSweepService service;

    @BeforeEach
    void setUp() {
        service = new TimelineOrphanItemSweepService(timelineItemService, timelineEventItemService,
                java.time.Clock.systemUTC());
        lenient().when(timelineEventItemService.findActivelyLinkedItemIds(anyCollection()))
                .thenReturn(Set.of());
    }

    @Test
    void observationAndStaleThresholdUseClockInSeoul() {
        var clock = java.time.Clock.fixed(java.time.Instant.parse("2026-09-11T18:30:00Z"), java.time.ZoneOffset.UTC);
        service = new TimelineOrphanItemSweepService(timelineItemService, timelineEventItemService,
                clock);
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
        assertThat(result.photoDeletions()).isEmpty();
        verify(timelineItemService).deleteByIds(List.of(11L));
    }

    @Test
    void validPhotoOrphanIsReturnedForS3DeletionAndKeepsRow() {
        TimelineItem item = photoItem(12L, FILENAME, "https://cdn.example.net/" + KEY_A);
        scan(item);
        claim(item);

        var result = service.sweepBatch(service.observeBatch(0, 2, 250));

        // 행은 S3 삭제가 확인된 뒤에만 지운다(#551).
        assertThat(result.photoDeletions()).containsExactly(new PhotoDeletion(12L, KEY_A));
        verify(timelineItemService).deleteByIds(List.of());
    }

    @Test
    void photoOrphanWithMixedPlaceShapesIsReturnedForS3Deletion() {
        // 저장본 places는 문자열 원소(옛 행)와 객체 원소가 섞여 있다(#620). 두 형태 모두 S3 삭제 대상이 된다.
        TimelineItem item = photoItem(18L, FILENAME, "https://cdn.example.net/" + KEY_A);
        ((ObjectNode) item.getPayload()).putArray("places")
                .add("옛 장소")
                .add(new ObjectMapper().createObjectNode()
                        .put("name", "새 장소").put("latitude", 37.5).put("longitude", 127.0));
        scan(item);
        claim(item);

        var result = service.sweepBatch(service.observeBatch(0, 2, 250));

        assertThat(result.photoDeletions()).containsExactly(new PhotoDeletion(18L, KEY_A));
    }

    @Test
    void unrestorableObjectKeySkipsS3AndDeletesRow() {
        TimelineItem broken = photoItem(17L, FILENAME,
                "https://cdn.example.net/" + NAMESPACE_A.substring(0, 40) + "[REDACTED_CARD]/photos/" + FILENAME);
        scan(broken);
        claim(broken);

        var result = service.sweepBatch(service.observeBatch(0, 2, 250));

        assertThat(result.invalidDeleted()).isEqualTo(1);
        assertThat(result.photoDeletions()).isEmpty();
        verify(timelineItemService).deleteByIds(List.of(17L));
    }

    @Test
    void orphansSharingObjectKeyAreBothReturnedForDeletion() {
        // 수용된 race/legacy 중복. S3 삭제는 멱등이라 둘 다 같은 key로 요청하고 둘 다 지워진다.
        TimelineItem first = photoItem(18L, FILENAME, "https://cdn.example.net/" + KEY_A);
        TimelineItem second = photoItem(19L, FILENAME, "https://cdn.example.net/" + KEY_A);
        scan(first, second);
        claim(first, second);

        var result = service.sweepBatch(service.observeBatch(0, 2, 250));

        assertThat(result.photoDeletions())
                .containsExactly(new PhotoDeletion(18L, KEY_A), new PhotoDeletion(19L, KEY_A));
    }

    @Test
    void deletePhotoItemsDeletesOnlyGivenItems() {
        service.deletePhotoItems(List.of(12L, 13L));

        verify(timelineItemService).deleteByIds(List.of(12L, 13L));
    }

    @Test
    void revalidationDropsItemsThatGainedActiveLinkAfterScan() {
        TimelineItem stillOrphan = photoItem(20L, FILENAME, "https://cdn.example.net/" + KEY_A);
        TimelineItem gainedJunction = notificationItem(21L);
        scan(stillOrphan, gainedJunction);
        claim(stillOrphan, gainedJunction);
        when(timelineEventItemService.findActivelyLinkedItemIds(List.of(20L, 21L))).thenReturn(Set.of(21L));

        var result = service.sweepBatch(service.observeBatch(0, 2, 250));

        assertThat(result.revalidationDropped()).isEqualTo(1);
        assertThat(result.photoDeletions()).containsExactly(new PhotoDeletion(20L, KEY_A));
        verify(timelineItemService).deleteByIds(List.of());
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

}
