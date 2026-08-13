package com.laimory.server.timeline.repository;

import static com.laimory.server.testsupport.SubjectMappingFixtures.ensureExists;
import static com.laimory.server.testsupport.TestSubjects.id;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.timeline.ItemType;
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

/** 실제 MySQL에서 두 transaction의 SKIP LOCKED claim 결과가 겹치지 않는지 검증한다. */
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
        jdbcTemplate.update("update timeline_photo_delete_jobs set available_at = '2000-01-01 00:00:00'");

        List<List<TimelinePhotoDeleteJob>> claims = claimConcurrently(
                () -> photoJobService.claimEligible(ROW_COUNT / 2));
        assertDisjointAndEventuallyDrained(
                claims,
                () -> photoJobService.claimEligible(ROW_COUNT),
                TimelinePhotoDeleteJob::getTimelinePhotoDeleteJobId);
    }

    @Test
    void draftWorkersClaimDisjointBoundedBatchesAndDoNotReclaimThemSameDay() throws Exception {
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
                + "set created_at = '2000-01-01 00:00:00', cleanup_available_at = '2000-01-01 00:00:00'");
        LocalDateTime cutoff = LocalDateTime.of(2000, 1, 2, 0, 0);

        List<List<TimelineDraftSourceItem>> claims = claimConcurrently(
                () -> draftSourceItemService.claimExpired(cutoff, ROW_COUNT / 2));
        assertDisjointAndEventuallyDrained(
                claims,
                () -> draftSourceItemService.claimExpired(cutoff, ROW_COUNT),
                TimelineDraftSourceItem::getTimelineDraftSourceItemId);
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
                        "https://cdn.example/" + filename)));
        return timelineItemRepository.save(item).getTimelineItemId();
    }

    private <T> List<List<T>> claimConcurrently(Supplier<List<T>> claim) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<List<T>> first = executor.submit(() -> {
                start.await();
                return claim.get();
            });
            Future<List<T>> second = executor.submit(() -> {
                start.await();
                return claim.get();
            });
            start.countDown();
            return List.of(first.get(), second.get());
        }
    }

    /**
     * SKIP LOCKED는 겹치지 않는 claim을 보장하지만 경합 중인 한 호출이 LIMIT를 모두 채우지는 않을 수 있다.
     * 최초 동시 claim과 후속 drain 전체에서 각 ID가 한 번만 나오고 같은 날 queue가 비는지를 검증한다.
     */
    private <T> void assertDisjointAndEventuallyDrained(
            List<List<T>> initialClaims,
            Supplier<List<T>> nextClaim,
            Function<T, Long> idExtractor) {
        Set<Long> claimedIds = new HashSet<>();
        initialClaims.forEach(batch -> addDisjoint(claimedIds, batch, idExtractor));

        int followUpClaims = 0;
        List<T> batch = nextClaim.get();
        while (!batch.isEmpty()) {
            assertThat(followUpClaims++).isLessThan(ROW_COUNT);
            addDisjoint(claimedIds, batch, idExtractor);
            batch = nextClaim.get();
        }
        assertThat(claimedIds).hasSize(ROW_COUNT);
    }

    private <T> void addDisjoint(Set<Long> claimedIds, List<T> batch, Function<T, Long> idExtractor) {
        List<Long> batchIds = batch.stream().map(idExtractor).toList();
        assertThat(batchIds).doesNotHaveDuplicates();
        assertThat(claimedIds).doesNotContainAnyElementsOf(batchIds);
        claimedIds.addAll(batchIds);
    }
}
