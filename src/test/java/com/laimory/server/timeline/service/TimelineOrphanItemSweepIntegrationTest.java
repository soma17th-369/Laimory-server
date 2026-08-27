package com.laimory.server.timeline.service;

import static com.laimory.server.testsupport.SubjectMappingFixtures.ensureExists;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.testsupport.SubjectMappingFixtures;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * orphan 스위퍼의 실 MySQL 계약 검증.
 *
 * <p>핵심은 세 가지다 — ① junction 0 Item이 규칙대로 수렴하는가, ② 같은 S3 객체를 가리키는 살아 있는
 * Item을 어떤 경우에도 놓치지 않는가(놓치면 사용자 사진이 삭제된다), ③ 동시 실행에서 job이 중복되거나
 * FK 위반으로 batch가 깨지지 않는가.
 *
 * <p>실행: docker compose up -d 후 ./gradlew integrationTest
 */
@SpringBootTest
@ActiveProfiles("docker")
@Tag("integration")
class TimelineOrphanItemSweepIntegrationTest {

    private static final LocalDate DATE = LocalDate.of(2000, 1, 5);
    private static final String ZONE = "Asia/Seoul";

    @Autowired
    private TimelineOrphanItemSweepService sweepService;
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
    private TimelinePhotoDeleteJobService timelinePhotoDeleteJobService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Set<Long> fixtureItemIds = new HashSet<>();

    private UUID subjectId;
    private Long recordId;
    private long cursor;

    @BeforeEach
    void setUp() {
        subjectId = UUID.randomUUID();
        ensureExists(jdbcTemplate, subjectId);
        fixtureItemIds.clear();
        recordId = dailyRecordRepository.save(DailyRecord.createDraft(subjectId, DATE, DATE.atTime(12, 0), ZONE))
                .getDailyRecordId();
        // 다른 fixture가 남긴 행을 건드리지 않도록 이번 테스트가 만든 첫 id 직전부터 훑는다.
        cursor = timelineItemRepository.findAll().stream()
                .mapToLong(TimelineItem::getTimelineItemId)
                .max()
                .orElse(0L);
    }

    @AfterEach
    void cleanUp() {
        List<Long> jobIds = timelinePhotoDeleteJobRepository.findAll().stream()
                .filter(job -> fixtureItemIds.contains(job.getTimelineItemId()))
                .map(TimelinePhotoDeleteJob::getTimelinePhotoDeleteJobId)
                .toList();
        if (!jobIds.isEmpty()) {
            timelinePhotoDeleteJobRepository.deleteAllByIdInBatch(jobIds);
        }
        dailyRecordRepository.findBySubjectIdAndRecordDate(subjectId, DATE)
                .ifPresent(record -> dailyRecordRepository.deleteById(record.getDailyRecordId()));
        List<Long> remaining = fixtureItemIds.stream()
                .filter(id -> timelineItemRepository.existsById(id))
                .toList();
        if (!remaining.isEmpty()) {
            timelineItemRepository.deleteAllByIdInBatch(remaining);
        }
        SubjectMappingFixtures.deleteSubjectScopedPushRows(jdbcTemplate, subjectId);
        jdbcTemplate.update("DELETE FROM user_subject_links WHERE subject_id = ?", subjectId.toString());
    }

    @Test
    void sqlDerivedNamespaceMatchesApplicationRule() {
        // 살아 있는 Item의 key를 SQL이 직접 계산하는 것이 이 기능의 안전장치다. 두 규칙이 어긋나면
        // 보호가 통째로 무력화되므로 값 일치를 못 박는다.
        String sqlNamespace = jdbcTemplate.queryForObject(
                "SELECT SHA2(UNHEX(REPLACE(?, '-', '')), 256)", String.class, subjectId.toString());

        assertThat(sqlNamespace).isEqualTo(PhotoObjectKeys.subjectNamespace(subjectId));
    }

    @Test
    void concurrentDetachRemnantConvergesToExactlyOneJob() {
        // #247 동시 해제 경합의 종단 상태 — 공유 PHOTO의 junction 두 줄이 모두 사라졌는데 job이 없다.
        String filename = filename(1);
        Long eventOne = saveEvent("첫 이벤트", 9);
        Long eventTwo = saveEvent("둘째 이벤트", 10);
        Long itemId = savePhoto("raw-a1", filename, eventOne, eventTwo);
        // 동시 해제 두 요청이 남긴 종단 상태를 직접 만든다(스레드 타이밍에 의존하지 않는 결정적 재현).
        jdbcTemplate.update("DELETE FROM timeline_event_items WHERE timeline_item_id = ?", itemId);

        var first = sweepService.sweepBatch(cursor, 250);
        var second = sweepService.sweepBatch(cursor, 250);

        assertThat(first.photoScheduled()).isEqualTo(1);
        assertThat(second.photoScheduled()).isZero();
        assertThat(jobsOfFixture()).extracting(TimelinePhotoDeleteJob::getObjectKey)
                .containsExactly(PhotoObjectKeys.subjectFullKey(filename, subjectId));
        // 원문 PHOTO Item은 worker가 S3 성공 뒤 지운다 — 스위퍼는 보존한다.
        assertThat(timelineItemRepository.existsById(itemId)).isTrue();
    }

    @Test
    void nonPhotoOrphanIsDeletedAndLinkedItemsAreUntouched() {
        Long eventId = saveEvent("유지 이벤트", 9);
        Long linked = saveCalendar("raw-linked", eventId);
        Long orphan = saveCalendar("raw-orphan");

        var result = sweepService.sweepBatch(cursor, 250);

        assertThat(result.nonPhotoDeleted()).isEqualTo(1);
        assertThat(timelineItemRepository.existsById(orphan)).isFalse();
        assertThat(timelineItemRepository.existsById(linked)).isTrue();
    }

    @Test
    void liveItemSharingObjectKeyIsProtectedEvenWhenItsPhotoUrlIsDamaged() {
        // #387 이전에 저장된 행은 photoUrl namespace가 손상돼 있을 수 있다. 그 Item이 살아 있으면
        // 보호 대상인데, URL로만 판정하면 보이지 않아 S3 원본이 지워진다.
        String filename = filename(2);
        Long eventId = saveEvent("살아있는 이벤트", 9);
        Long live = savePhoto("raw-live", filename, eventId);
        damagePhotoUrlNamespace(live);
        Long orphan = savePhotoWithKey("raw-orphan-b2", filename,
                PhotoObjectKeys.subjectFullKey(filename, subjectId));

        var result = sweepService.sweepBatch(cursor, 250);

        assertThat(result.keyShared()).isEqualTo(1);
        assertThat(result.photoScheduled()).isZero();
        assertThat(jobsOfFixture()).isEmpty();
        assertThat(timelineItemRepository.existsById(orphan)).isFalse();
        assertThat(timelineItemRepository.existsById(live)).isTrue();
    }

    @Test
    void liveItemWithSameFilenameInAnotherNamespaceDoesNotBlockDeletion() {
        String filename = filename(3);
        UUID otherSubject = UUID.randomUUID();
        ensureExists(jdbcTemplate, otherSubject);
        Long otherRecordId = dailyRecordRepository.save(
                        DailyRecord.createDraft(otherSubject, DATE, DATE.atTime(12, 0), ZONE))
                .getDailyRecordId();
        Long otherEventId = timelineEventRepository.save(TimelineEvent.of(otherRecordId,
                        TimelineEventType.UNKNOWN, DATE.atTime(9, 0), null, "남의 이벤트", null, null, null, null))
                .getTimelineEventId();
        Long otherLive = savePhotoWithKey("raw-other", filename,
                PhotoObjectKeys.subjectFullKey(filename, otherSubject));
        timelineEventItemRepository.save(TimelineEventItem.of(otherEventId, otherLive));
        Long orphan = savePhotoWithKey("raw-mine", filename,
                PhotoObjectKeys.subjectFullKey(filename, subjectId));

        try {
            var result = sweepService.sweepBatch(cursor, 250);

            assertThat(result.photoScheduled()).isEqualTo(1);
            assertThat(result.keyShared()).isZero();
            assertThat(jobsOfFixture()).extracting(TimelinePhotoDeleteJob::getTimelineItemId)
                    .containsExactly(orphan);
        } finally {
            dailyRecordRepository.deleteById(otherRecordId);
            SubjectMappingFixtures.deleteSubjectScopedPushRows(jdbcTemplate, otherSubject);
            jdbcTemplate.update("DELETE FROM user_subject_links WHERE subject_id = ?", otherSubject.toString());
        }
    }

    @Test
    void duplicateOrphansSharingOneObjectKeyConvergeToLowestIdOwner() {
        String filename = filename(4);
        String objectKey = PhotoObjectKeys.subjectFullKey(filename, subjectId);
        Long lower = savePhotoWithKey("raw-lower", filename, objectKey);
        Long higher = savePhotoWithKey("raw-higher", filename, objectKey);

        var result = sweepService.sweepBatch(cursor, 250);

        assertThat(result.photoScheduled()).isEqualTo(1);
        assertThat(result.keyShared()).isEqualTo(1);
        assertThat(jobsOfFixture()).extracting(TimelinePhotoDeleteJob::getTimelineItemId)
                .containsExactly(lower);
        assertThat(timelineItemRepository.existsById(lower)).isTrue();
        assertThat(timelineItemRepository.existsById(higher)).isFalse();
    }

    @Test
    void duplicateOrphansInSeparateBatchesStillConvergeToOneJob() {
        String filename = filename(5);
        String objectKey = PhotoObjectKeys.subjectFullKey(filename, subjectId);
        Long lower = savePhotoWithKey("raw-lower-e5", filename, objectKey);
        Long higher = savePhotoWithKey("raw-higher-e5", filename, objectKey);

        var first = sweepService.sweepBatch(cursor, 1);
        var second = sweepService.sweepBatch(first.nextCursor(), 1);

        assertThat(first.photoScheduled() + second.photoScheduled()).isEqualTo(1);
        assertThat(jobsOfFixture()).extracting(TimelinePhotoDeleteJob::getTimelineItemId)
                .containsExactly(lower);
        assertThat(timelineItemRepository.existsById(higher)).isFalse();
    }

    @Test
    void damagedPhotoUrlDropsJobAndDeletesRow() {
        Long orphan = savePhotoWithKey("raw-damaged", filename(6), "not-a-valid-object-key");

        var result = sweepService.sweepBatch(cursor, 250);

        assertThat(result.invalidDeleted()).isEqualTo(1);
        assertThat(jobsOfFixture()).isEmpty();
        assertThat(timelineItemRepository.existsById(orphan)).isFalse();
    }

    @Test
    void itemWithExistingJobIsExcludedFromCandidates() {
        // job이 있는 Item은 후보 조회 단계에서 빠진다(worker 소유라 스위퍼가 건드리면 FK 위반).
        //
        // NOTE: "탐색 이후 다른 transaction이 job을 commit"하는 순서(잠금 하 current read 재검증이
        // 막는 경로)는 이 테스트가 아니라 TimelineOrphanItemSweepServiceTest의
        // concurrentlyCreatedJobPreservesRowInsteadOfDeleting이 mock으로만 덮는다. 실 MySQL에서
        // 그 순서를 재현하려면 서비스 내부에 개입 지점이 필요해 통합 커버리지는 비어 있다.
        String filename = filename(7);
        Long orphan = savePhotoWithKey("raw-g7", filename,
                PhotoObjectKeys.subjectFullKey(filename, subjectId));
        timelinePhotoDeleteJobService.insertIfAbsent(orphan, PhotoObjectKeys.subjectFullKey(filename, subjectId));

        var result = sweepService.sweepBatch(cursor, 250);

        assertThat(result.scanned()).isZero();
        assertThat(timelineItemRepository.existsById(orphan)).isTrue();
        assertThat(jobsOfFixture()).hasSize(1);
    }

    @Test
    void concurrentSweepsCreateExactlyOneJobAndNeitherFails() throws Exception {
        String filename = filename(8);
        String objectKey = PhotoObjectKeys.subjectFullKey(filename, subjectId);
        Long orphan = savePhotoWithKey("raw-h8", filename, objectKey);
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        long startCursor = cursor;

        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Integer> first = executor.submit(() -> {
                start.await();
                return template.execute(status -> sweepService.sweepBatch(startCursor, 250).photoScheduled());
            });
            Future<Integer> second = executor.submit(() -> {
                start.await();
                return template.execute(status -> sweepService.sweepBatch(startCursor, 250).photoScheduled());
            });
            start.countDown();
            assertThat(first.get() + second.get()).isEqualTo(1);
        }

        assertThat(jobsOfFixture()).extracting(TimelinePhotoDeleteJob::getTimelineItemId).containsExactly(orphan);
        assertThat(timelineItemRepository.existsById(orphan)).isTrue();
    }

    private List<TimelinePhotoDeleteJob> jobsOfFixture() {
        return timelinePhotoDeleteJobRepository.findAll().stream()
                .filter(job -> fixtureItemIds.contains(job.getTimelineItemId()))
                .toList();
    }

    /** UUIDv7 + 허용 확장자 전체 일치를 통과해야 key 복원이 된다 — 마지막 그룹은 반드시 hex 12자다. */
    private String filename(int seed) {
        return "0190b2c3-d4e5-7f6a-8b9c-" + String.format("%012x", seed) + ".jpg";
    }

    private Long saveEvent(String title, int hour) {
        return timelineEventRepository.save(TimelineEvent.of(recordId, TimelineEventType.UNKNOWN,
                        DATE.atTime(hour, 0), null, title, null, null, null, null))
                .getTimelineEventId();
    }

    private Long saveCalendar(String rawId, Long... eventIds) {
        TimelineItem item = timelineItemRepository.save(TimelineItem.of(ItemType.CALENDAR, rawId,
                DATE.atTime(9, 0), null,
                objectMapper.valueToTree(new CalendarPayload(rawId, null, null, false))));
        fixtureItemIds.add(item.getTimelineItemId());
        for (Long eventId : eventIds) {
            timelineEventItemRepository.save(TimelineEventItem.of(eventId, item.getTimelineItemId()));
        }
        return item.getTimelineItemId();
    }

    private Long savePhoto(String rawId, String filename, Long... eventIds) {
        return savePhotoWithKey(rawId, filename, PhotoObjectKeys.subjectFullKey(filename, subjectId), eventIds);
    }

    private Long savePhotoWithKey(String rawId, String filename, String objectKey, Long... eventIds) {
        PhotoPayload payload = new PhotoPayload(filename, "content://fixture/" + rawId, null, null, null,
                null, null, "https://cdn.example/" + objectKey);
        TimelineItem item = timelineItemRepository.save(TimelineItem.of(ItemType.PHOTO, rawId,
                DATE.atTime(9, 0), null, objectMapper.valueToTree(payload)));
        fixtureItemIds.add(item.getTimelineItemId());
        for (Long eventId : eventIds) {
            timelineEventItemRepository.save(TimelineEventItem.of(eventId, item.getTimelineItemId()));
        }
        return item.getTimelineItemId();
    }

    /** redaction이 namespace 중간을 토큰으로 바꾼 #387 이전 저장본을 재현한다. */
    private void damagePhotoUrlNamespace(Long itemId) {
        jdbcTemplate.update(
                "UPDATE timeline_items SET payload = JSON_SET(payload, '$.photoUrl', ?) "
                        + "WHERE timeline_item_id = ?",
                "https://cdn.example/" + "0".repeat(40) + "[REDACTED_CARD]/photos/x.jpg", itemId);
    }
}
