package com.laimory.server.timeline.repository;

import static com.laimory.server.testsupport.SubjectMappingFixtures.ensureExists;
import static com.laimory.server.testsupport.TestSubjects.id;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.timeline.ItemType;
import com.laimory.server.timeline.TimelinePhotoDeleteJobStatus;
import com.laimory.server.timeline.entity.TimelineDraftSourceItem;
import com.laimory.server.timeline.entity.TimelineItem;
import com.laimory.server.timeline.entity.TimelinePhotoDeleteJob;
import com.laimory.server.timeline.payload.CalendarPayload;
import com.laimory.server.timeline.payload.PhotoPayload;
import com.laimory.server.timeline.service.TimelineDraftSourceItemService;
import com.laimory.server.timeline.service.TimelinePhotoDeleteJobService;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** 실제 MySQL에서 두 transaction의 고정 PK 분배 결과가 겹치지 않는지 검증한다. */
@SpringBootTest
@ActiveProfiles("docker")
@Tag("integration")
class DistributedScheduledClaimIntegrationTest {

    private static final int ROW_COUNT = 20;
    private static final UUID SUBJECT_ID = id(96L);

    @Autowired
    private TimelinePhotoDeleteJobService photoJobService;

    @Autowired
    private TimelineDraftSourceItemService draftSourceItemService;

    @Autowired
    private TimelinePhotoDeleteJobRepository photoJobRepository;

    @Autowired
    private TimelineDraftSourceItemRepository draftSourceItemRepository;

    @Autowired
    private TimelineItemRepository timelineItemRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private List<Long> photoItemIds = List.of();

    @BeforeEach
    void setUp() {
        photoJobRepository.deleteAllInBatch();
        draftSourceItemRepository.deleteAllInBatch();
        ensureExists(jdbcTemplate, SUBJECT_ID);
    }

    @AfterEach
    void cleanUp() {
        photoJobRepository.deleteAllInBatch();
        draftSourceItemRepository.deleteAllInBatch();
        timelineItemRepository.deleteAllByIdInBatch(photoItemIds);
    }

    @Test
    void photoWorkersClaimDisjointBatchesAndDoNotReclaimThemSameDay() throws Exception {
        photoItemIds = java.util.stream.IntStream.range(0, ROW_COUNT)
                .mapToObj(this::savePhotoItem)
                .toList();
        for (int index = 0; index < ROW_COUNT; index++) {
            assertThat(photoJobService.insertIfAbsent(
                    photoItemIds.get(index), "claim-test/photos/" + index + ".jpg")).isTrue();
        }
        // KST 처리 창(D+1~D+3) 안의 어제 생성·어제 마지막 갱신으로 맞춰 오늘 run의 claim 대상이 되게 한다.
        LocalDateTime withinWindow = LocalDateTime.now().toLocalDate().atStartOfDay().minusHours(12);
        jdbcTemplate.update("update timeline_photo_delete_jobs set created_at = ?, updated_at = ?",
                withinWindow, withinWindow);

        java.util.concurrent.atomic.AtomicInteger owner = new java.util.concurrent.atomic.AtomicInteger();
        List<List<TimelinePhotoDeleteJob>> claims = claimConcurrently(
                () -> photoJobService.claimEligible(owner.getAndIncrement(), 2, ROW_COUNT));
        Set<Long> selected = new HashSet<>();
        claims.forEach(batch -> addDisjoint(selected, batch, TimelinePhotoDeleteJob::getTimelinePhotoDeleteJobId));
        assertThat(selected).hasSize(ROW_COUNT);
        assertThat(photoJobService.claimEligible(0, 2, ROW_COUNT)).isEmpty();
        assertThat(photoJobService.claimEligible(1, 2, ROW_COUNT)).isEmpty();
        assertThat(photoJobRepository.findAll())
                .extracting(TimelinePhotoDeleteJob::getStatus)
                .containsOnly(TimelinePhotoDeleteJobStatus.PROCESSING);
    }

    @Test
    void duplicatePhotoCompletionsConvergeWithoutPessimisticPreLock() throws Exception {
        long itemId = savePhotoItem(ROW_COUNT + 1);
        photoItemIds = List.of(itemId);
        assertThat(photoJobService.insertIfAbsent(itemId, "claim-test/photos/completion.jpg")).isTrue();
        TimelinePhotoDeleteJob job = photoJobRepository.findAll().getFirst();

        List<Integer> completed = runConcurrently(() -> photoJobService.completeSucceeded(List.of(job)));

        assertThat(completed).containsExactlyInAnyOrder(1, 0);
        assertThat(photoJobRepository.findById(job.getTimelinePhotoDeleteJobId())).isEmpty();
        assertThat(timelineItemRepository.findById(itemId)).isEmpty();
    }

    @Test
    void draftWorkersSelectDisjointBatchesAndRetryRemainingRows() throws Exception {
        for (int index = 0; index < ROW_COUNT; index++) {
            draftSourceItemRepository.save(TimelineDraftSourceItem.of(
                    UUID.randomUUID().toString(),
                    SUBJECT_ID,
                    ItemType.CALENDAR,
                    "raw-" + index,
                    LocalDateTime.of(2000, 1, 1, 9, 0),
                    null,
                    objectMapper.valueToTree(new CalendarPayload("event-" + index, null, null, false))));
        }
        jdbcTemplate.update("update timeline_draft_source_items "
                + "set created_at = '2000-01-01 00:00:00'");
        LocalDateTime cutoff = LocalDateTime.of(2000, 1, 2, 0, 0);

        java.util.concurrent.atomic.AtomicInteger owner = new java.util.concurrent.atomic.AtomicInteger();
        List<List<TimelineDraftSourceItem>> selections = claimConcurrently(
                () -> draftSourceItemService.findExpired(cutoff, owner.getAndIncrement(), 2, ROW_COUNT));
        Set<Long> selected = new HashSet<>();
        selections.forEach(batch -> addDisjoint(selected, batch, TimelineDraftSourceItem::getTimelineDraftSourceItemId));
        assertThat(selected).hasSize(ROW_COUNT);
        assertThat(draftSourceItemService.findExpired(cutoff, 0, 2, ROW_COUNT)).hasSize(ROW_COUNT / 2);
        assertThat(draftSourceItemService.findExpired(cutoff, 1, 2, ROW_COUNT)).hasSize(ROW_COUNT / 2);
    }

    @Test
    void sparseAndSkewedPrimaryKeysHaveOneOwnerForTwoAndFourWorkers() {
        photoItemIds = java.util.stream.IntStream.range(0, 24).mapToObj(this::savePhotoItem).toList();
        for (int index = 0; index < photoItemIds.size(); index++) {
            photoJobService.insertIfAbsent(photoItemIds.get(index), "partition/photos/" + index + ".jpg");
            draftSourceItemRepository.save(TimelineDraftSourceItem.of(UUID.randomUUID().toString(), SUBJECT_ID,
                    ItemType.CALENDAR, "partition-" + index, null, null,
                    objectMapper.valueToTree(new CalendarPayload("fixture", null, null, false))));
        }
        jdbcTemplate.update("DELETE FROM timeline_photo_delete_jobs "
                + "WHERE MOD(timeline_photo_delete_job_id, 4)=1 OR MOD(timeline_photo_delete_job_id, 7)=0");
        jdbcTemplate.update("DELETE FROM timeline_draft_source_items "
                + "WHERE MOD(timeline_draft_source_item_id, 4)=1 OR MOD(timeline_draft_source_item_id, 7)=0");
        jdbcTemplate.update("UPDATE timeline_draft_source_items SET created_at='2000-01-01'");
        var expectedPhotoIds = photoJobRepository.findAll().stream()
                .map(TimelinePhotoDeleteJob::getTimelinePhotoDeleteJobId).toList();
        var expectedDraftIds = draftSourceItemRepository.findAll().stream()
                .map(TimelineDraftSourceItem::getTimelineDraftSourceItemId).toList();
        for (int total : List.of(2, 4)) {
            var yesterday = LocalDateTime.now().toLocalDate().atStartOfDay().minusHours(12);
            jdbcTemplate.update("UPDATE timeline_photo_delete_jobs SET created_at=?, updated_at=?, status='PENDING'",
                    yesterday, yesterday);
            Set<Long> photos = new HashSet<>();
            Set<Long> drafts = new HashSet<>();
            for (int worker = 0; worker < total; worker++) {
                var photoBatch = photoJobService.claimEligible(worker, total, 250);
                var draftBatch = draftSourceItemService.findExpired(
                        LocalDateTime.of(2000, 1, 2, 0, 0), worker, total, 250);
                final int owner = worker;
                assertThat(photoBatch).allMatch(job -> (job.getTimelinePhotoDeleteJobId() - 1) % total == owner);
                assertThat(draftBatch).allMatch(row -> (row.getTimelineDraftSourceItemId() - 1) % total == owner);
                addDisjoint(photos, photoBatch, TimelinePhotoDeleteJob::getTimelinePhotoDeleteJobId);
                addDisjoint(drafts, draftBatch, TimelineDraftSourceItem::getTimelineDraftSourceItemId);
            }
            assertThat(photos).containsExactlyInAnyOrderElementsOf(expectedPhotoIds);
            assertThat(drafts).containsExactlyInAnyOrderElementsOf(expectedDraftIds);
        }
    }

    private long savePhotoItem(int index) {
        String filename = "claim-" + index + ".jpg";
        TimelineItem item = TimelineItem.of(
                ItemType.PHOTO,
                "raw-" + index,
                LocalDateTime.of(2000, 1, 1, 9, 0),
                null,
                objectMapper.valueToTree(new PhotoPayload(
                        filename,
                        "content://claim/" + index,
                        null,
                        null,
                        null,
                        null, null,
                        "https://cdn.example/" + filename)));
        return timelineItemRepository.save(item).getTimelineItemId();
    }

    private <T> List<List<T>> claimConcurrently(Supplier<List<T>> claim) throws Exception {
        return runConcurrently(claim);
    }

    private <T> List<T> runConcurrently(Supplier<T> operation) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<T> first = executor.submit(() -> {
                start.await();
                return operation.get();
            });
            Future<T> second = executor.submit(() -> {
                start.await();
                return operation.get();
            });
            start.countDown();
            return List.of(first.get(), second.get());
        }
    }

    private <T> void addDisjoint(Set<Long> claimedIds, List<T> batch, Function<T, Long> idExtractor) {
        if (batch.isEmpty()) {
            return;
        }
        List<Long> batchIds = batch.stream().map(idExtractor).toList();
        assertThat(batchIds).doesNotHaveDuplicates();
        assertThat(claimedIds).doesNotContainAnyElementsOf(batchIds);
        claimedIds.addAll(batchIds);
    }
}
