package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static com.laimory.server.testsupport.SubjectMappingFixtures.ensureExists;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.testsupport.SubjectMappingFixtures;
import com.laimory.server.timeline.EmotionType;
import com.laimory.server.timeline.ItemType;
import com.laimory.server.timeline.TimelineEventType;
import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.entity.TimelineEvent;
import com.laimory.server.timeline.entity.TimelineEventItem;
import com.laimory.server.timeline.entity.TimelineEventPlaceCandidate;
import com.laimory.server.timeline.entity.TimelineItem;
import com.laimory.server.timeline.payload.CalendarPayload;
import com.laimory.server.timeline.payload.PhotoPayload;
import com.laimory.server.timeline.photo.PhotoObjectKeys;
import com.laimory.server.timeline.repository.DailyRecordRepository;
import com.laimory.server.timeline.repository.TimelineEventItemRepository;
import com.laimory.server.timeline.repository.TimelineEventPlaceCandidateRepository;
import com.laimory.server.timeline.repository.TimelineEventRepository;
import com.laimory.server.timeline.repository.TimelineItemRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 삭제 DB 트랜잭션 bean ↔ 실 MySQL 계약 검증(mockito론 못 잡음):
 * Event·DailyRecord 삭제는 soft delete다(#568) — 행·junction·Item이 남고 활성 조회에서 빠진다. 감정·장소명·주소는
 * 지워진다. record 삭제는 하위 활성 Event에 같은 시각을 찍는다. 같은 날짜 유일성은 활성 행에만 걸린다(V11 생성 컬럼
 * unique).
 *
 * 실행: docker compose up -d 후 ./gradlew integrationTest
 */
@SpringBootTest
@ActiveProfiles("docker")
@Tag("integration")
class TimelineSoftDeleteIntegrationTest {

    // 매 테스트 임의 사용자로 persistent local DB의 기존 record와 격리하고, 날짜도 다른 고정 fixture와 구분한다.
    private static final LocalDate DATE = LocalDate.of(2000, 1, 3);
    private static final String ZONE = "Asia/Seoul";

    @Autowired
    private DailyRecordRepository dailyRecordRepository;
    @Autowired
    private TimelineEventRepository timelineEventRepository;
    @Autowired
    private TimelineItemRepository timelineItemRepository;
    @Autowired
    private TimelineEventItemRepository timelineEventItemRepository;
    @Autowired
    private TimelineEventPlaceCandidateRepository timelineEventPlaceCandidateRepository;
    @Autowired
    private TimelineDeletionTransactionService timelineDeletionTransactionService;
    @Autowired
    private DailyRecordService dailyRecordService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Set<Long> fixtureItemIds = new HashSet<>();

    private UUID subjectId;
    private Long recordId;

    @BeforeEach
    void setUp() {
        subjectId = UUID.randomUUID();
        ensureExists(jdbcTemplate, subjectId);
        fixtureItemIds.clear();
        recordId = dailyRecordRepository.save(DailyRecord.createDraft(subjectId, DATE, DATE.atTime(12, 0), ZONE))
                .getDailyRecordId();
    }

    @AfterEach
    void cleanUp() {
        deleteFixtureRecord();
        deleteFixtureItems();
        // 가입 transaction이 만든 subject 축 push 행(#314)이 남아 있으면 mapping 삭제가 FK RESTRICT에 막힌다.
        SubjectMappingFixtures.deleteSubjectScopedPushRows(jdbcTemplate, subjectId);
        jdbcTemplate.update("DELETE FROM user_subject_links WHERE subject_id = ?", subjectId.toString());
    }

    private void deleteFixtureItems() {
        if (!fixtureItemIds.isEmpty()) {
            timelineItemRepository.deleteAllByIdInBatch(fixtureItemIds);
        }
    }

    private void deleteFixtureRecord() {
        // soft delete된 record·Event까지 지운다(활성 조회는 삭제 행을 못 본다) — 계정 삭제용 무필터 조회를 쓴다.
        // Item은 record cascade 대상이 아니므로 junction 경유로 수집해 함께 지운다(테스트 잔존 방지).
        List<Long> recordIds = dailyRecordRepository.findIdsBySubjectIdAfterId(subjectId, 0L, PageRequest.of(0, 100));
        if (recordIds.isEmpty()) {
            return;
        }
        List<Long> itemIds = timelineEventItemRepository.findItemIdsByDailyRecordIdIn(recordIds);
        recordIds.forEach(dailyRecordRepository::deleteById);
        if (!itemIds.isEmpty()) {
            timelineItemRepository.deleteAllByIdInBatch(itemIds);
        }
    }

    private Long saveEvent(String title, int hour) {
        return timelineEventRepository.save(
                        TimelineEvent.of(recordId, TimelineEventType.UNKNOWN, DATE.atTime(hour, 0), null, title, null, null,
                                "카페", "서울시 중구", null, null))
                .getTimelineEventId();
    }

    private Long saveEventWithCoordinatesAndCandidates(String title, int hour, String... candidateNames) {
        Long eventId = timelineEventRepository.save(
                        TimelineEvent.of(recordId, TimelineEventType.MEAL, DATE.atTime(hour, 0), null, title,
                                "{place}에서 점심", null, candidateNames[0], "서울시 성동구", 37.5445, 127.0559))
                .getTimelineEventId();
        for (String name : candidateNames) {
            timelineEventPlaceCandidateRepository.save(TimelineEventPlaceCandidate.of(eventId, name));
        }
        return eventId;
    }

    private List<String> candidateNames(Long eventId) {
        return timelineEventPlaceCandidateRepository
                .findByTimelineEventIdInOrderByTimelineEventPlaceCandidateIdAsc(List.of(eventId)).stream()
                .map(TimelineEventPlaceCandidate::getName)
                .toList();
    }

    private Long saveItemLinkedTo(String rawId, int hour, Long... eventIds) {
        TimelineItem item = timelineItemRepository.save(TimelineItem.of(ItemType.CALENDAR, rawId,
                DATE.atTime(hour, 0), null, objectMapper.valueToTree(new CalendarPayload(rawId, null, null, false))));
        fixtureItemIds.add(item.getTimelineItemId());
        for (Long eventId : eventIds) {
            timelineEventItemRepository.save(TimelineEventItem.of(eventId, item.getTimelineItemId()));
        }
        return item.getTimelineItemId();
    }

    private Long savePhotoLinkedTo(String rawId, String filename, int hour, Long... eventIds) {
        PhotoPayload payload = new PhotoPayload(
                filename, "content://fixture/" + rawId, null, null, null,
                null, null,
                "https://cdn.example/" + PhotoObjectKeys.subjectFullKey(filename, subjectId));
        TimelineItem item = timelineItemRepository.save(TimelineItem.of(
                ItemType.PHOTO, rawId, DATE.atTime(hour, 0), null, objectMapper.valueToTree(payload)));
        fixtureItemIds.add(item.getTimelineItemId());
        for (Long eventId : eventIds) {
            timelineEventItemRepository.save(TimelineEventItem.of(eventId, item.getTimelineItemId()));
        }
        return item.getTimelineItemId();
    }

    @Test
    void deleteEventKeepsRowLinksAndItemsButHidesItAndClearsLocation() {
        Long targetEventId = saveEvent("삭제 대상", 9);
        Long siblingEventId = saveEvent("남는 이벤트", 10);
        Long photoItemId = savePhotoLinkedTo(
                "raw-target", "0190b2c3-d4e5-7f6a-8b9c-0d1e2f3a4b5c.jpg", 9, targetEventId);

        timelineDeletionTransactionService.deleteEvent(subjectId, targetEventId);

        // 활성 조회에서는 사라진다.
        assertThat(timelineEventRepository.findByTimelineEventIdAndDeletedAtIsNull(targetEventId)).isEmpty();
        // 행은 남고 장소명·주소만 지워진다.
        assertThat(timelineEventRepository.findById(targetEventId)).hasValueSatisfying(event -> {
            assertThat(event.getDeletedAt()).isNotNull();
            assertThat(event.getTitle()).isEqualTo("삭제 대상");
            assertThat(event.getPlace()).isNull();
            assertThat(event.getAddress()).isNull();
        });
        // junction·Item은 남는다.
        assertThat(timelineEventItemRepository.findByTimelineEventId(targetEventId))
                .extracting(TimelineEventItem::getTimelineItemId)
                .containsExactly(photoItemId);
        assertThat(timelineItemRepository.findById(photoItemId)).isPresent();
        // 형제 Event와 record는 그대로다.
        assertThat(timelineEventRepository.findByTimelineEventIdAndDeletedAtIsNull(siblingEventId))
                .hasValueSatisfying(event -> assertThat(event.getPlace()).isEqualTo("카페"));
        assertThat(dailyRecordRepository.findByDailyRecordIdAndDeletedAtIsNull(recordId)).isPresent();
    }

    @Test
    void deleteDailyRecordStampsActiveEventsWithRecordTimeAndKeepsEarlierEventDeletionTime() {
        Long earlierDeletedEventId = saveEvent("먼저 지운 이벤트", 8);
        Long activeEventId = saveEvent("남은 이벤트", 9);
        Long itemId = saveItemLinkedTo("raw-record", 9, activeEventId);
        dailyRecordRepository.markSaved(recordId, subjectId, EmotionType.HAPPY, DATE.atTime(23, 0));
        timelineDeletionTransactionService.deleteEvent(subjectId, earlierDeletedEventId);
        LocalDateTime earlierDeletedAt = timelineEventRepository.findById(earlierDeletedEventId).orElseThrow()
                .getDeletedAt();

        timelineDeletionTransactionService.deleteDailyRecord(subjectId, recordId);

        DailyRecord record = dailyRecordRepository.findById(recordId).orElseThrow();
        assertThat(record.getDeletedAt()).isNotNull();
        assertThat(record.getEmotionType()).isNull();
        assertThat(dailyRecordRepository.findByDailyRecordIdAndDeletedAtIsNull(recordId)).isEmpty();
        assertThat(timelineEventRepository.findById(activeEventId)).hasValueSatisfying(event -> {
            assertThat(event.getDeletedAt()).isEqualTo(record.getDeletedAt());
            assertThat(event.getPlace()).isNull();
            assertThat(event.getAddress()).isNull();
        });
        assertThat(timelineEventRepository.findById(earlierDeletedEventId).orElseThrow().getDeletedAt())
                .isEqualTo(earlierDeletedAt)
                .isBefore(record.getDeletedAt());
        assertThat(timelineEventItemRepository.findByTimelineEventId(activeEventId))
                .extracting(TimelineEventItem::getTimelineItemId)
                .containsExactly(itemId);
        assertThat(timelineItemRepository.findById(itemId)).isPresent();
    }

    @Test
    void deleteEventClearsCoordinatesAndDeletesOnlyItsPlaceCandidates() {
        Long targetEventId = saveEventWithCoordinatesAndCandidates("삭제 대상", 9, "성수동 카페", "△△공원");
        Long siblingEventId = saveEventWithCoordinatesAndCandidates("남는 이벤트", 12, "을지로 식당");

        timelineDeletionTransactionService.deleteEvent(subjectId, targetEventId);

        assertThat(timelineEventRepository.findById(targetEventId)).hasValueSatisfying(event -> {
            assertThat(event.getDeletedAt()).isNotNull();
            assertThat(event.getLatitude()).isNull();
            assertThat(event.getLongitude()).isNull();
            assertThat(event.getSubtitle()).isEqualTo("{place}에서 점심");
        });
        assertThat(candidateNames(targetEventId)).isEmpty();
        assertThat(timelineEventRepository.findById(siblingEventId)).hasValueSatisfying(event -> {
            assertThat(event.getLatitude()).isEqualTo(37.5445);
            assertThat(event.getLongitude()).isEqualTo(127.0559);
        });
        assertThat(candidateNames(siblingEventId)).containsExactly("을지로 식당");
    }

    @Test
    void deleteDailyRecordClearsCoordinatesAndDeletesPlaceCandidatesOfEveryEvent() {
        Long firstEventId = saveEventWithCoordinatesAndCandidates("점심", 12, "성수동 카페", "△△공원");
        Long secondEventId = saveEventWithCoordinatesAndCandidates("저녁", 19, "을지로 식당");

        timelineDeletionTransactionService.deleteDailyRecord(subjectId, recordId);

        assertThat(timelineEventRepository.findAllById(List.of(firstEventId, secondEventId)))
                .allSatisfy(event -> {
                    assertThat(event.getDeletedAt()).isNotNull();
                    assertThat(event.getLatitude()).isNull();
                    assertThat(event.getLongitude()).isNull();
                });
        assertThat(candidateNames(firstEventId)).isEmpty();
        assertThat(candidateNames(secondEventId)).isEmpty();
    }

    @Test
    void hardDeletingRecordCascadesToPlaceCandidates() {
        // 탈퇴 삭제(#302)는 daily_records 행을 지운다 — timeline_events를 거쳐 후보까지 FK CASCADE로 지워져야 한다.
        Long eventId = saveEventWithCoordinatesAndCandidates("점심", 12, "성수동 카페", "△△공원");

        dailyRecordRepository.deleteById(recordId);

        assertThat(timelineEventRepository.findById(eventId)).isEmpty();
        assertThat(candidateNames(eventId)).isEmpty();
    }

    @Test
    void deletedDateCanBeRecordedAndDeletedAgainWhileActiveRecordStaysUnique() {
        timelineDeletionTransactionService.deleteDailyRecord(subjectId, recordId);

        DailyRecord reRecorded = dailyRecordService.findOrCreateDraft(subjectId, DATE, DATE.atTime(20, 0), ZONE);

        assertThat(reRecorded.getDailyRecordId()).isNotEqualTo(recordId);
        assertThatThrownBy(() -> dailyRecordRepository.saveAndFlush(
                DailyRecord.createDraft(subjectId, DATE, DATE.atTime(21, 0), ZONE)))
                .isInstanceOf(DataIntegrityViolationException.class);
        // 같은 날짜의 두 번째 삭제도 unique에 막히지 않는다(삭제 행은 active_marker가 NULL).
        timelineDeletionTransactionService.deleteDailyRecord(subjectId, reRecorded.getDailyRecordId());
        assertThat(dailyRecordRepository.findIdsBySubjectIdAfterId(subjectId, 0L, PageRequest.of(0, 100)))
                .containsExactlyInAnyOrder(recordId, reRecorded.getDailyRecordId());
        assertThat(dailyRecordRepository.findBySubjectIdAndRecordDateAndDeletedAtIsNull(subjectId, DATE)).isEmpty();
    }
}
