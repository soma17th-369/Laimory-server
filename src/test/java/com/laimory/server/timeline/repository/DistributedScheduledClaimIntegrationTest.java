package com.laimory.server.timeline.repository;

import static com.laimory.server.testsupport.SubjectMappingFixtures.ensureExists;
import static com.laimory.server.testsupport.TestSubjects.id;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.timeline.ItemType;
import com.laimory.server.timeline.entity.TimelineDraftSourceItem;
import com.laimory.server.timeline.payload.CalendarPayload;
import com.laimory.server.timeline.service.TimelineDraftSourceItemService;
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

/** 실제 MySQL에서 스케줄러별 분배와 처리 상태 전이를 검증한다. */
@SpringBootTest
@ActiveProfiles("docker")
@Tag("integration")
class DistributedScheduledClaimIntegrationTest {

    private static final int ROW_COUNT = 20;
    private static final UUID SUBJECT_ID = id(96L);

    @Autowired
    private TimelineDraftSourceItemService draftSourceItemService;

    @Autowired
    private TimelineDraftSourceItemRepository draftSourceItemRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        draftSourceItemRepository.deleteAllInBatch();
        ensureExists(jdbcTemplate, SUBJECT_ID);
    }

    @AfterEach
    void cleanUp() {
        draftSourceItemRepository.deleteAllInBatch();
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
    void sparseDraftPrimaryKeysHaveOneOwnerForTwoAndFourWorkers() {
        for (int index = 0; index < 24; index++) {
            draftSourceItemRepository.save(TimelineDraftSourceItem.of(UUID.randomUUID().toString(), SUBJECT_ID,
                    ItemType.CALENDAR, "partition-" + index, null, null,
                    objectMapper.valueToTree(new CalendarPayload("fixture", null, null, false))));
        }
        jdbcTemplate.update("DELETE FROM timeline_draft_source_items "
                + "WHERE MOD(timeline_draft_source_item_id, 4)=1 OR MOD(timeline_draft_source_item_id, 7)=0");
        jdbcTemplate.update("UPDATE timeline_draft_source_items SET created_at='2000-01-01'");
        var expectedIds = draftSourceItemRepository.findAll().stream()
                .map(TimelineDraftSourceItem::getTimelineDraftSourceItemId).toList();
        for (int total : List.of(2, 4)) {
            Set<Long> selected = new HashSet<>();
            for (int worker = 0; worker < total; worker++) {
                var batch = draftSourceItemService.findExpired(LocalDateTime.of(2000, 1, 2, 0, 0), worker, total, 250);
                final int owner = worker;
                assertThat(batch).allMatch(row -> (row.getTimelineDraftSourceItemId() - 1) % total == owner);
                addDisjoint(selected, batch, TimelineDraftSourceItem::getTimelineDraftSourceItemId);
            }
            assertThat(selected).containsExactlyInAnyOrderElementsOf(expectedIds);
        }
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
