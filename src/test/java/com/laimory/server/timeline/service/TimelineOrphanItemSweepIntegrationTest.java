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
import com.laimory.server.timeline.photo.S3PhotoStorageService;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * orphan 스위퍼의 실 MySQL 계약 검증.
 *
 * <p>핵심은 세 가지다 — ① 활성 Event 연결이 없는 Item(junction 0 또는 soft delete된 Event에만 연결, #569)이
 * 규칙대로 수렴하는가, ② 유효 PHOTO는 S3 삭제가 확인된 뒤에만 행이 지워지고 실패하면 다음 실행까지 남는가(#551),
 * ③ 동시 실행에서 같은 PHOTO를 두 번 넘기거나 FK 위반으로 batch가 깨지지 않는가. S3는 mock이다.
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
    private TimelineDeletionTransactionService deletionTransactionService;
    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("timelineOrphanSweepExecutor")
    private org.springframework.core.task.TaskExecutor orphanSweepExecutor;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private TimelineItemService itemService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PlatformTransactionManager transactionManager;

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
        List<Long> jobIds = timelinePhotoDeleteJobRepository.findAll().stream()
                .filter(job -> fixtureItemIds.contains(job.getTimelineItemId()))
                .map(TimelinePhotoDeleteJob::getTimelinePhotoDeleteJobId)
                .toList();
        if (!jobIds.isEmpty()) {
            timelinePhotoDeleteJobRepository.deleteAllByIdInBatch(jobIds);
        }
        // soft delete된 record까지 지운다(활성 조회는 삭제 행을 못 본다) — 계정 삭제용 무필터 id 조회를 쓴다.
        dailyRecordRepository.findIdsBySubjectIdAfterId(subjectId, 0L, PageRequest.of(0, 100))
                .forEach(dailyRecordRepository::deleteById);
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
    void photoOrphanIsReturnedAgainUntilItsItemIsDeleted() {
        // S3 결과를 받기 전에는 행이 남으므로 다음 실행이 같은 PHOTO를 다시 넘긴다(#551).
        String filename = filename(1);
        Long eventId = saveEvent("해제 이벤트", 9);
        Long itemId = savePhoto("raw-a1", filename, eventId);
        jdbcTemplate.update("DELETE FROM timeline_event_items WHERE timeline_item_id = ?", itemId);
        var expected = new TimelineOrphanItemSweepService.PhotoDeletion(
                itemId, PhotoObjectKeys.subjectFullKey(filename, subjectId));

        var first = sweep(0, 1, 250);
        var second = sweep(0, 1, 250);

        assertThat(first.photoDeletions()).containsExactly(expected);
        assertThat(second.photoDeletions()).containsExactly(expected);
        assertThat(timelineItemRepository.existsById(itemId)).isTrue();
    }

    @Test
    void nonPhotoOrphanIsDeletedAndLinkedItemsAreUntouched() {
        Long eventId = saveEvent("유지 이벤트", 9);
        Long linked = saveCalendar("raw-linked", eventId);
        Long orphan = saveCalendar("raw-orphan");

        var result = sweep(0, 1, 250);

        assertThat(result.nonPhotoDeleted()).isEqualTo(1);
        assertThat(timelineItemRepository.existsById(orphan)).isFalse();
        assertThat(timelineItemRepository.existsById(linked)).isTrue();
    }

    @Test
    void nonPhotoOfDeletedEventIsDeletedWithItsJunction() {
        Long eventId = saveEvent("삭제 이벤트", 9);
        Long calendar = saveCalendar("raw-deleted-calendar", eventId);
        deletionTransactionService.deleteEvent(subjectId, eventId);

        var result = sweep(0, 1, 250);

        assertThat(result.nonPhotoDeleted()).isEqualTo(1);
        assertThat(timelineItemRepository.existsById(calendar)).isFalse();
        assertThat(timelineEventItemRepository.findByTimelineItemIdIn(List.of(calendar))).isEmpty();
    }

    @Test
    void photoOfDeletedEventIsReturnedForS3DeletionAndKeepsRow() {
        String filename = filename(11);
        Long eventId = saveEvent("삭제 이벤트", 9);
        Long photo = savePhoto("raw-deleted-photo", filename, eventId);
        deletionTransactionService.deleteEvent(subjectId, eventId);

        var result = sweep(0, 1, 250);

        assertThat(result.photoDeletions()).containsExactly(new TimelineOrphanItemSweepService.PhotoDeletion(
                photo, PhotoObjectKeys.subjectFullKey(filename, subjectId)));
        assertThat(timelineItemRepository.existsById(photo)).isTrue();
    }

    @Test
    void sweeperDeletesPhotoItemOnlyAfterS3ConfirmsDeletion() {
        String filename = filename(14);
        String objectKey = PhotoObjectKeys.subjectFullKey(filename, subjectId);
        Long eventId = saveEvent("삭제 이벤트", 9);
        Long photo = savePhoto("raw-sweeper-photo", filename, eventId);
        deletionTransactionService.deleteEvent(subjectId, eventId);
        S3PhotoStorageService s3 = org.mockito.Mockito.mock(S3PhotoStorageService.class);
        org.mockito.Mockito.when(s3.deleteAll(org.mockito.ArgumentMatchers.anyList()))
                .thenAnswer(invocation -> new S3PhotoStorageService.BatchDeleteResult(
                        Set.copyOf(invocation.<List<String>>getArgument(0)), java.util.Map.of(), Set.of()));

        sweeperWith(s3, orphanSweepExecutor).sweepOrphanItems();

        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10))
                .until(() -> !timelineItemRepository.existsById(photo));
        org.mockito.Mockito.verify(s3).deleteAll(org.mockito.ArgumentMatchers.argThat(keys -> keys.contains(objectKey)));
        assertThat(timelineEventItemRepository.findByTimelineItemIdIn(List.of(photo))).isEmpty();
    }

    @Test
    void sweeperKeepsPhotoItemAndFirstObservationWhenS3Fails() {
        String filename = filename(15);
        String objectKey = PhotoObjectKeys.subjectFullKey(filename, subjectId);
        Long eventId = saveEvent("삭제 이벤트", 9);
        Long photo = savePhoto("raw-sweeper-fail", filename, eventId);
        deletionTransactionService.deleteEvent(subjectId, eventId);
        S3PhotoStorageService s3 = org.mockito.Mockito.mock(S3PhotoStorageService.class);
        org.mockito.Mockito.when(s3.deleteAll(org.mockito.ArgumentMatchers.anyList()))
                .thenAnswer(invocation -> new S3PhotoStorageService.BatchDeleteResult(Set.of(),
                        invocation.<List<String>>getArgument(0).stream()
                                .collect(java.util.stream.Collectors.toMap(key -> key, key -> "InternalError")),
                        Set.of()));

        // 실패 경로는 DB 상태만 본다 — 같은 스레드에서 실행해 두 run의 제출이 queue 0 executor와 겹치지 않게 한다.
        sweeperWith(s3, Runnable::run).sweepOrphanItems();
        var observed = timelineItemRepository.findById(photo).orElseThrow();
        sweeperWith(s3, Runnable::run).sweepOrphanItems();
        org.mockito.Mockito.verify(s3, org.mockito.Mockito.times(2))
                .deleteAll(org.mockito.ArgumentMatchers.argThat(keys -> keys.contains(objectKey)));

        // 실패한 PHOTO는 남아 다음 실행이 다시 요청하고, 최초 관측 시각은 덮어쓰지 않는다(72h 경보의 기준).
        var retried = timelineItemRepository.findById(photo).orElseThrow();
        assertThat(retried.getModifiedBy()).isEqualTo("ORPHAN_SWEEPER");
        assertThat(retried.getUpdatedAt()).isEqualTo(observed.getUpdatedAt());
    }

    @Test
    void sweepingItemsOfDeletedEventKeepsTheEventRow() {
        Long eventId = saveEvent("삭제 이벤트", 9);
        saveCalendar("raw-deleted-keep-event", eventId);
        deletionTransactionService.deleteEvent(subjectId, eventId);

        sweep(0, 1, 250);

        assertThat(timelineEventRepository.findById(eventId)).isPresent();
    }

    @Test
    void itemSharedWithActiveEventIsNotSwept() {
        Long deletedEvent = saveEvent("삭제 이벤트", 9);
        Long activeEvent = saveEvent("유지 이벤트", 10);
        Long shared = savePhoto("raw-shared", filename(12), deletedEvent, activeEvent);
        deletionTransactionService.deleteEvent(subjectId, deletedEvent);

        var result = sweep(0, 1, 250);

        assertThat(result.selected()).isZero();
        assertThat(timelineItemRepository.existsById(shared)).isTrue();
        assertThat(jobsOfFixture()).isEmpty();
    }

    @Test
    void itemsOfDeletedDailyRecordAreSwept() {
        Long eventId = saveEvent("하루 삭제 이벤트", 9);
        Long calendar = saveCalendar("raw-record-calendar", eventId);
        deletionTransactionService.deleteDailyRecord(subjectId, recordId);

        var result = sweep(0, 1, 250);

        assertThat(result.nonPhotoDeleted()).isEqualTo(1);
        assertThat(timelineItemRepository.existsById(calendar)).isFalse();
    }

    @Test
    void detachLeavingOnlyDeletedEventLinkMakesPhotoSweepable() {
        // 연결 해제는 Item을 분류하지 않는다(#551). 삭제된 Event의 연결만 남은 PHOTO는 스위퍼가 정리한다.
        String filename = filename(13);
        Long deletedEvent = saveEvent("삭제 이벤트", 9);
        Long activeEvent = saveEvent("해제 이벤트", 10);
        Long shared = savePhoto("raw-detach", filename, deletedEvent, activeEvent);
        deletionTransactionService.deleteEvent(subjectId, deletedEvent);
        deletionTransactionService.detachEventItem(subjectId, activeEvent, shared);
        assertThat(jobsOfFixture()).isEmpty();

        var result = sweep(0, 1, 250);

        assertThat(result.photoDeletions()).extracting(TimelineOrphanItemSweepService.PhotoDeletion::timelineItemId)
                .containsExactly(shared);
    }

    @Test
    void staleCountIncludesItemLinkedOnlyToDeletedEvent() {
        var now = java.time.LocalDateTime.of(2026, 9, 12, 3, 30);
        Long eventId = saveEvent("삭제 이벤트", 9);
        Long item = saveCalendar("raw-stale-deleted", eventId);
        deletionTransactionService.deleteEvent(subjectId, eventId);
        jdbcTemplate.update("UPDATE timeline_items SET modified_by='ORPHAN_SWEEPER', updated_at=? "
                + "WHERE timeline_item_id=?", now.minusHours(72), item);

        assertThat(itemService.countStaleObservedOrphans(0, 1, now.minusHours(72))).isEqualTo(1);
    }

    @Test
    void damagedPhotoUrlSkipsS3AndDeletesRow() {
        Long orphan = savePhotoWithKey("raw-damaged", filename(6), "not-a-valid-object-key");

        var result = sweep(0, 1, 250);

        assertThat(result.invalidDeleted()).isEqualTo(1);
        assertThat(result.photoDeletions()).isEmpty();
        assertThat(timelineItemRepository.existsById(orphan)).isFalse();
    }

    @Test
    void itemWithExistingJobIsExcludedFromCandidates() {
        // job이 있는 Item은 후보 조회 단계에서 빠진다(worker 소유라 스위퍼가 건드리면 FK 위반).
        String filename = filename(7);
        Long orphan = savePhotoWithKey("raw-g7", filename,
                PhotoObjectKeys.subjectFullKey(filename, subjectId));
        timelinePhotoDeleteJobService.insertIfAbsent(orphan, PhotoObjectKeys.subjectFullKey(filename, subjectId));

        var result = sweep(0, 1, 250);

        assertThat(result.selected()).isZero();
        assertThat(timelineItemRepository.existsById(orphan)).isTrue();
        assertThat(jobsOfFixture()).hasSize(1);
    }

    @Test
    void concurrentSweepsReturnPhotoExactlyOnceAndNeitherFails() throws Exception {
        String filename = filename(8);
        String objectKey = PhotoObjectKeys.subjectFullKey(filename, subjectId);
        Long orphan = savePhotoWithKey("raw-h8", filename, objectKey);
        TransactionTemplate template = new TransactionTemplate(transactionManager);

        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Integer> first = executor.submit(() -> {
                start.await();
                return template.execute(status -> sweep(0, 2, 250).photoDeletions().size());
            });
            Future<Integer> second = executor.submit(() -> {
                start.await();
                return template.execute(status -> sweep(1, 2, 250).photoDeletions().size());
            });
            start.countDown();
            assertThat(first.get() + second.get()).isEqualTo(1);
        }

        assertThat(timelineItemRepository.existsById(orphan)).isTrue();
    }

    @Test
    void observationSurvivesProcessingRollbackAndIsWrittenOnlyOnce() {
        Long itemId = savePhoto("raw-observe", filename(91));
        var before = timelineItemRepository.findById(itemId).orElseThrow().getCreatedAt();
        List<Long> candidates = sweepService.observeBatch(0, 1, 250);
        var first = timelineItemRepository.findById(itemId).orElseThrow();
        assertThat(first.getModifiedBy()).isEqualTo("ORPHAN_SWEEPER");
        assertThat(first.getCreatedAt()).isEqualTo(before);
        org.mockito.Mockito.doThrow(new IllegalStateException("synthetic processing failure"))
                .when(itemService).deleteByIds(org.mockito.ArgumentMatchers.anyCollection());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> sweepService.sweepBatch(candidates))
                .isInstanceOf(IllegalStateException.class);
        org.mockito.Mockito.reset(itemService);
        sweepService.observeBatch(0, 1, 250);
        var retried = timelineItemRepository.findById(itemId).orElseThrow();
        assertThat(retried.getUpdatedAt()).isEqualTo(first.getUpdatedAt());
        assertThat(retried.getModifiedBy()).isEqualTo("ORPHAN_SWEEPER");
        assertThat(sweepService.sweepBatch(candidates).photoDeletions()).hasSize(1);
    }

    @Test
    void staleCountIncludesBoundaryAndObservedRowsBeyondCurrentBatchOnly() {
        var now = java.time.LocalDateTime.of(2026, 9, 12, 3, 30);
        Long exact = saveCalendar("raw-exact");
        Long recent = saveCalendar("raw-recent");
        Long unobserved = saveCalendar("raw-unobserved");
        Long outsideBatch = saveCalendar("raw-outside");
        Long linked = saveCalendar("raw-linked-old", saveEvent("linked", 9));
        Long withJob = savePhoto("raw-with-job", filename(92));
        for (Long id : List.of(exact, outsideBatch, linked, withJob)) {
            jdbcTemplate.update("UPDATE timeline_items SET modified_by='ORPHAN_SWEEPER', updated_at=? "
                    + "WHERE timeline_item_id=?", now.minusHours(72), id);
        }
        jdbcTemplate.update("UPDATE timeline_items SET modified_by='ORPHAN_SWEEPER', updated_at=? "
                + "WHERE timeline_item_id=?", now.minusHours(72).plusNanos(1000), recent);
        jdbcTemplate.update("UPDATE timeline_items SET updated_at=? WHERE timeline_item_id=?", now.minusDays(8), unobserved);
        timelinePhotoDeleteJobService.insertIfAbsent(withJob, PhotoObjectKeys.subjectFullKey(filename(92), subjectId));
        assertThat(sweepService.observeBatch(0, 1, 1)).containsExactly(exact);
        assertThat(itemService.countStaleObservedOrphans(0, 1, now.minusHours(72))).isEqualTo(2);
        assertThat(itemService.countStaleObservedOrphans(0, 1, now.minusHours(72).minusNanos(1000))).isZero();
        assertThat(itemService.countStaleObservedOrphans(0, 2, now.minusHours(72))
                + itemService.countStaleObservedOrphans(1, 2, now.minusHours(72))).isEqualTo(2);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> itemService.deleteByIds(List.of(exact)));
        assertThat(itemService.countStaleObservedOrphans(0, 1, now.minusHours(72))).isEqualTo(1);
    }

    /** S3 mock으로 만든 스위퍼. docker profile 기본 담당(서버 1대·slot 1개)을 쓴다. */
    private TimelineOrphanItemSweeper sweeperWith(S3PhotoStorageService s3,
                                                  org.springframework.core.task.TaskExecutor executor) {
        return new TimelineOrphanItemSweeper(sweepService, s3,
                new TimelineOrphanItemSweeperProperties(true, 250, 0, 1, 1), executor);
    }

    private TimelineOrphanItemSweepService.SweepBatchResult sweep(int workerIndex, int total, int limit) {
        return sweepService.sweepBatch(sweepService.observeBatch(workerIndex, total, limit));
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
}
