package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doThrow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.timeline.ItemType;
import com.laimory.server.timeline.TimelineEventType;
import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.entity.TimelineEvent;
import com.laimory.server.timeline.entity.TimelineEventItem;
import com.laimory.server.timeline.entity.TimelineItem;
import com.laimory.server.timeline.entity.TimelinePhotoDeleteJob;
import com.laimory.server.timeline.payload.CalendarPayload;
import com.laimory.server.timeline.payload.PhotoPayload;
import com.laimory.server.timeline.photo.PhotoObjectKeys;
import com.laimory.server.timeline.repository.DailyRecordRepository;
import com.laimory.server.timeline.repository.TimelineEventItemRepository;
import com.laimory.server.timeline.repository.TimelineEventRepository;
import com.laimory.server.timeline.repository.TimelineItemRepository;
import com.laimory.server.timeline.repository.TimelinePhotoDeleteJobRepository;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * 삭제 DB 트랜잭션 bean ↔ 실 MySQL N:M cascade/orphan 계약 검증(mockito론 못 잡음):
 * Event/Record 행 삭제 시 자기 junction은 DB FK {@code ON DELETE CASCADE}로 소멸한다. 삭제 대상에만
 * 연결된 non-PHOTO Item은 즉시 삭제하고, valid PHOTO Item은 job과 함께 보존했다가 S3 성공 완료 경계에서
 * 최종 삭제하며 shared Item은 유지된다.
 *
 * 실행: docker compose up -d 후 ./gradlew integrationTest
 */
@SpringBootTest
@ActiveProfiles("docker")
@Tag("integration")
class TimelineDeletionCascadeIntegrationTest {

    // 다른 통합 테스트의 고정 날짜(2000-01-01 콜백, 2000-01-02 편집 경합)와 (user_id, record_date) 유니크 충돌을 피한다.
    private static final LocalDate DATE = LocalDate.of(2000, 1, 3);
    private static final String ZONE = "Asia/Seoul";
    private static final long USER_ID = 0L;

    @Autowired
    private DailyRecordRepository dailyRecordRepository;
    @Autowired
    private TimelineEventRepository timelineEventRepository;
    @Autowired
    private TimelineItemRepository timelineItemRepository;
    @Autowired
    private TimelineEventItemRepository timelineEventItemRepository;
    @Autowired
    private TimelinePhotoDeleteJobRepository timelinePhotoDeleteJobRepository;
    @Autowired
    private TimelineDeletionTransactionService timelineDeletionTransactionService;
    @Autowired
    private TimelinePhotoDeleteCompletionService timelinePhotoDeleteCompletionService;
    @MockitoSpyBean
    private TimelineItemService timelineItemService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private Long recordId;

    @BeforeEach
    void setUp() {
        deletePendingPhotoJobsAndItems();
        deleteFixtureRecord();
        recordId = dailyRecordRepository.save(DailyRecord.createDraft(USER_ID, DATE, DATE.atTime(12, 0), ZONE))
                .getDailyRecordId();
    }

    @AfterEach
    void cleanUp() {
        deleteFixtureRecord();
        deletePendingPhotoJobsAndItems();
    }

    private void deletePendingPhotoJobsAndItems() {
        List<Long> pendingItemIds = timelinePhotoDeleteJobRepository.findAll().stream()
                .map(TimelinePhotoDeleteJob::getTimelineItemId)
                .toList();
        timelinePhotoDeleteJobRepository.deleteAllInBatch();
        if (!pendingItemIds.isEmpty()) {
            timelineItemRepository.deleteAllByIdInBatch(pendingItemIds);
        }
    }

    private void deleteFixtureRecord() {
        // Item은 record cascade 대상이 아니므로 junction 경유로 수집해 함께 지운다(테스트 잔존 방지).
        dailyRecordRepository.findByUserIdAndRecordDate(USER_ID, DATE).ifPresent(record -> {
            List<Long> eventIds = timelineEventRepository
                    .findByDailyRecordIdOrderByStartAtAscTimelineEventIdAsc(record.getDailyRecordId()).stream()
                    .map(TimelineEvent::getTimelineEventId)
                    .toList();
            List<Long> itemIds = eventIds.isEmpty() ? List.of()
                    : timelineEventItemRepository.findByTimelineEventIdIn(eventIds).stream()
                            .map(TimelineEventItem::getTimelineItemId)
                            .distinct()
                            .toList();
            dailyRecordRepository.deleteById(record.getDailyRecordId());
            if (!itemIds.isEmpty()) {
                timelineItemRepository.deleteAllByIdInBatch(itemIds);
            }
        });
    }

    private Long saveEvent(String title, int hour) {
        return timelineEventRepository.save(
                        TimelineEvent.of(recordId, TimelineEventType.UNKNOWN, DATE.atTime(hour, 0), null, title, null))
                .getTimelineEventId();
    }

    private Long saveItemLinkedTo(String rawId, int hour, Long... eventIds) {
        TimelineItem item = timelineItemRepository.save(TimelineItem.of(ItemType.CALENDAR, rawId,
                DATE.atTime(hour, 0), null, objectMapper.valueToTree(new CalendarPayload(rawId, null, null, false))));
        for (Long eventId : eventIds) {
            timelineEventItemRepository.save(TimelineEventItem.of(eventId, item.getTimelineItemId()));
        }
        return item.getTimelineItemId();
    }

    private Long savePhotoLinkedTo(String rawId, String filename, int hour, Long... eventIds) {
        PhotoPayload payload = new PhotoPayload(
                filename, "content://fixture/" + rawId, null, null, null,
                "https://cdn.example/" + PhotoObjectKeys.fullKey(filename, USER_ID));
        TimelineItem item = timelineItemRepository.save(TimelineItem.of(
                ItemType.PHOTO, rawId, DATE.atTime(hour, 0), null, objectMapper.valueToTree(payload)));
        for (Long eventId : eventIds) {
            timelineEventItemRepository.save(TimelineEventItem.of(eventId, item.getTimelineItemId()));
        }
        return item.getTimelineItemId();
    }

    @Test
    void deleteEvent_deletesOrphanItem_keepsSharedItemAndSibling() {
        Long targetEventId = saveEvent("삭제 대상", 9);
        Long siblingEventId = saveEvent("남는 이벤트", 10);
        String exclusiveFilename = "0190b2c3-d4e5-7f6a-8b9c-0d1e2f3a4b5c.jpg";
        Long exclusiveItemId = savePhotoLinkedTo("raw-exclusive", exclusiveFilename, 9, targetEventId);
        Long sharedItemId = savePhotoLinkedTo(
                "raw-shared", "0190b2c3-d4e5-7f6a-8b9c-0d1e2f3a4b5d.jpg",
                9, targetEventId, siblingEventId);

        TimelineDeletionTransactionService.DeletionResult result =
                timelineDeletionTransactionService.deleteEvent(USER_ID, targetEventId);

        // Event/junction hard delete와 PHOTO Item/job 보존이 같은 commit에 반영된다.
        assertThat(result).isEqualTo(new TimelineDeletionTransactionService.DeletionResult(1, 1, 0));
        assertThat(timelineEventRepository.findById(targetEventId)).isEmpty();
        assertThat(timelineEventItemRepository.findByTimelineEventId(targetEventId)).isEmpty();
        assertThat(timelineItemRepository.findById(exclusiveItemId)).isPresent();
        assertThat(timelinePhotoDeleteJobRepository.findAll())
                .singleElement()
                .satisfies(job -> {
                    assertThat(job.getTimelineItemId()).isEqualTo(exclusiveItemId);
                    assertThat(job.getObjectKey()).isEqualTo(PhotoObjectKeys.fullKey(exclusiveFilename, USER_ID));
                });
        // shared Item과 형제 이벤트·그 연결·record는 유지된다.
        assertThat(timelineItemRepository.findById(sharedItemId)).isPresent();
        assertThat(timelineEventRepository.findById(siblingEventId)).isPresent();
        assertThat(timelineEventItemRepository.findByTimelineEventId(siblingEventId))
                .extracting(TimelineEventItem::getTimelineItemId)
                .containsExactly(sharedItemId);
        assertThat(dailyRecordRepository.findById(recordId)).isPresent();
    }

    @Test
    void deleteEvent_hardDeleteFailure_rollsBackJobAndAllTimelineRows() {
        Long targetEventId = saveEvent("롤백 대상", 9);
        String filename = "0190b2c3-d4e5-7f6a-8b9c-0d1e2f3a4b5f.jpg";
        Long itemId = savePhotoLinkedTo("raw-rollback", filename, 9, targetEventId);
        doThrow(new IllegalStateException("forced hard delete failure"))
                .when(timelineItemService).deleteByIds(anyCollection());

        assertThatThrownBy(() -> timelineDeletionTransactionService.deleteEvent(USER_ID, targetEventId))
                .isInstanceOf(IllegalStateException.class);

        assertThat(timelinePhotoDeleteJobRepository.findAll()).isEmpty();
        assertThat(timelineEventRepository.findById(targetEventId)).isPresent();
        assertThat(timelineEventItemRepository.findByTimelineEventId(targetEventId))
                .extracting(TimelineEventItem::getTimelineItemId)
                .containsExactly(itemId);
        assertThat(timelineItemRepository.findById(itemId)).isPresent();
    }

    @Test
    void deleteLastEvent_keepsDailyRecord() {
        Long onlyEventId = saveEvent("마지막 이벤트", 9);
        Long itemId = saveItemLinkedTo("raw-last", 9, onlyEventId);

        timelineDeletionTransactionService.deleteEvent(USER_ID, onlyEventId);

        // 마지막 Event를 지워도 DailyRecord는 유지된다 — 하루 전체 제거는 DailyRecord 삭제 API만 담당.
        assertThat(timelineEventRepository.findById(onlyEventId)).isEmpty();
        assertThat(timelineItemRepository.findById(itemId)).isEmpty();
        assertThat(dailyRecordRepository.findById(recordId)).isPresent();
        assertThat(timelineEventRepository.findByDailyRecordIdOrderByStartAtAscTimelineEventIdAsc(recordId)).isEmpty();
    }

    @Test
    void deleteDailyRecord_cascadesEventsAndJunction_deletesOrphanItems() {
        Long event1 = saveEvent("이벤트1", 9);
        Long event2 = saveEvent("이벤트2", 10);
        String filename = "0190b2c3-d4e5-7f6a-8b9c-0d1e2f3a4b5e.jpg";
        Long item1 = savePhotoLinkedTo("raw-all-1", filename, 9, event1);
        // record 안 두 event가 공유하는 Item — record 전체 삭제라 함께 소멸해야 한다.
        Long item2 = saveItemLinkedTo("raw-all-2", 10, event1, event2);

        TimelineDeletionTransactionService.DeletionResult result =
                timelineDeletionTransactionService.deleteDailyRecord(USER_ID, recordId);

        assertThat(result).isEqualTo(new TimelineDeletionTransactionService.DeletionResult(1, 0, 0));
        assertThat(dailyRecordRepository.findById(recordId)).isEmpty();
        assertThat(timelineEventRepository.findById(event1)).isEmpty();
        assertThat(timelineEventRepository.findById(event2)).isEmpty();
        assertThat(timelineItemRepository.findById(item1)).isPresent();
        assertThat(timelineItemRepository.findById(item2)).isEmpty();
        assertThat(timelineEventItemRepository.findByTimelineEventIdIn(List.of(event1, event2))).isEmpty();
        assertThat(timelinePhotoDeleteJobRepository.findAll())
                .extracting(TimelinePhotoDeleteJob::getObjectKey)
                .containsExactly(PhotoObjectKeys.fullKey(filename, USER_ID));
    }

    @Test
    void completeSucceeded_deletesOriginalPhotoItemAndJobTogether() {
        Long targetEventId = saveEvent("완료 대상", 9);
        String filename = "0190b2c3-d4e5-7f6a-8b9c-0d1e2f3a4b60.jpg";
        Long itemId = savePhotoLinkedTo("raw-complete", filename, 9, targetEventId);
        timelineDeletionTransactionService.deleteEvent(USER_ID, targetEventId);
        TimelinePhotoDeleteJob job = timelinePhotoDeleteJobRepository.findAll().getFirst();

        timelinePhotoDeleteCompletionService.completeSucceeded(List.of(job));

        assertThat(timelinePhotoDeleteJobRepository.findAll()).isEmpty();
        assertThat(timelineItemRepository.findById(itemId)).isEmpty();
    }

    @Test
    void completeSucceeded_itemDeleteFailure_rollsBackJobDeleteAndKeepsBothRows() {
        Long targetEventId = saveEvent("완료 롤백 대상", 9);
        String filename = "0190b2c3-d4e5-7f6a-8b9c-0d1e2f3a4b61.jpg";
        Long itemId = savePhotoLinkedTo("raw-complete-rollback", filename, 9, targetEventId);
        timelineDeletionTransactionService.deleteEvent(USER_ID, targetEventId);
        TimelinePhotoDeleteJob job = timelinePhotoDeleteJobRepository.findAll().getFirst();
        doThrow(new IllegalStateException("forced completion item delete failure"))
                .when(timelineItemService).deleteByIds(List.of(itemId));

        assertThatThrownBy(() -> timelinePhotoDeleteCompletionService.completeSucceeded(List.of(job)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(timelinePhotoDeleteJobRepository.findById(job.getTimelinePhotoDeleteJobId())).isPresent();
        assertThat(timelineItemRepository.findById(itemId)).isPresent();
    }
}
