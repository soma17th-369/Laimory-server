package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static com.laimory.server.testsupport.SubjectMappingFixtures.ensureExists;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.common.error.BusinessException;
import com.laimory.server.testsupport.SubjectMappingFixtures;
import com.laimory.server.timeline.ItemType;
import com.laimory.server.timeline.TimelineEventType;
import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.entity.TimelineEvent;
import com.laimory.server.timeline.entity.TimelineEventItem;
import com.laimory.server.timeline.entity.TimelineItem;
import com.laimory.server.timeline.entity.TimelinePhotoDeleteJob;
import com.laimory.server.timeline.payload.PhotoPayload;
import com.laimory.server.timeline.photo.PhotoObjectKeys;
import com.laimory.server.timeline.repository.DailyRecordRepository;
import com.laimory.server.timeline.repository.TimelineEventItemRepository;
import com.laimory.server.timeline.repository.TimelineEventRepository;
import com.laimory.server.timeline.repository.TimelineItemRepository;
import com.laimory.server.timeline.repository.TimelinePhotoDeleteJobRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Event-Item 연결 해제 ↔ 실 MySQL 계약 검증(mockito론 못 잡음): junction 한 줄만 지워지고 shared Item은
 * 유지되며, 마지막 참조 해제는 PHOTO Item/job을 보존한다. junction 삭제는 영향 행 수를 반환하는 직접
 * DELETE라 같은 junction을 두 요청이 동시에 끊어도 하나만 성공하고 후발은 404다(stale-state 500 없음).
 * 서로 다른 junction의 동시 해제가 겹쳐 job 없는 orphan이 남는 드문 경합은 orphan 스위퍼(후속)가 수렴
 * 대상으로 맡는다 — 여기서는 검증하지 않는다.
 *
 * 실행: docker compose up -d 후 ./gradlew integrationTest
 */
@SpringBootTest
@ActiveProfiles("docker")
@Tag("integration")
class TimelineEventItemDetachIntegrationTest {

    // 매 테스트 임의 사용자로 persistent local DB의 기존 record/job과 격리하고, 날짜도 다른 고정 fixture와 구분한다.
    private static final LocalDate DATE = LocalDate.of(2000, 1, 5);
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
    private TimelinePhotoDeleteJobRepository timelinePhotoDeleteJobRepository;
    @Autowired
    private TimelineDeletionTransactionService timelineDeletionTransactionService;
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
        List<Long> fixtureJobIds = findFixturePhotoDeleteJobs().stream()
                .map(TimelinePhotoDeleteJob::getTimelinePhotoDeleteJobId)
                .toList();
        if (!fixtureJobIds.isEmpty()) {
            timelinePhotoDeleteJobRepository.deleteAllByIdInBatch(fixtureJobIds);
        }
        if (!fixtureItemIds.isEmpty()) {
            timelineItemRepository.deleteAllByIdInBatch(fixtureItemIds);
        }
        dailyRecordRepository.findBySubjectIdAndRecordDate(subjectId, DATE)
                .ifPresent(record -> dailyRecordRepository.deleteById(record.getDailyRecordId()));
        // 가입 transaction이 만든 subject 축 push 행(#314)이 남아 있으면 mapping 삭제가 FK RESTRICT에 막힌다.
        SubjectMappingFixtures.deleteSubjectScopedPushRows(jdbcTemplate, subjectId);
        jdbcTemplate.update("DELETE FROM user_subject_links WHERE subject_id = ?", subjectId.toString());
    }

    private List<TimelinePhotoDeleteJob> findFixturePhotoDeleteJobs() {
        return timelinePhotoDeleteJobRepository.findAll().stream()
                .filter(job -> fixtureItemIds.contains(job.getTimelineItemId()))
                .toList();
    }

    private Long saveEvent(String title, int hour) {
        return timelineEventRepository.save(
                        TimelineEvent.of(recordId, TimelineEventType.UNKNOWN, DATE.atTime(hour, 0), null, title, null, null, null, null))
                .getTimelineEventId();
    }

    private Long savePhotoLinkedTo(String rawId, String filename, int hour, Long... eventIds) {
        PhotoPayload payload = new PhotoPayload(
                filename, "content://fixture/" + rawId, null, null, null,
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
    void detachEventItem_sharedPhoto_removesOnlyTargetJunction() {
        Long targetEventId = saveEvent("해제 대상", 9);
        Long siblingEventId = saveEvent("공유 이벤트", 10);
        Long itemId = savePhotoLinkedTo(
                "raw-detach-shared", "0190b2c3-d4e5-7f6a-8b9c-0d1e2f3a4c01.jpg",
                9, targetEventId, siblingEventId);

        TimelineDeletionTransactionService.DeletionResult result =
                timelineDeletionTransactionService.detachEventItem(subjectId, targetEventId, itemId);

        assertThat(result).isEqualTo(new TimelineDeletionTransactionService.DeletionResult(0, 1, 0));
        // 대상 junction만 사라지고 Event·Item·형제 연결·record는 그대로다.
        assertThat(timelineEventItemRepository.findByTimelineEventId(targetEventId)).isEmpty();
        assertThat(timelineEventItemRepository.findByTimelineEventId(siblingEventId))
                .extracting(TimelineEventItem::getTimelineItemId)
                .containsExactly(itemId);
        assertThat(timelineEventRepository.findById(targetEventId)).isPresent();
        assertThat(timelineItemRepository.findById(itemId)).isPresent();
        assertThat(findFixturePhotoDeleteJobs()).isEmpty();
    }

    @Test
    void detachEventItem_lastReference_enqueuesJobAndPreservesItem() {
        Long targetEventId = saveEvent("마지막 참조", 9);
        String filename = "0190b2c3-d4e5-7f6a-8b9c-0d1e2f3a4c02.jpg";
        Long itemId = savePhotoLinkedTo("raw-detach-last", filename, 9, targetEventId);

        TimelineDeletionTransactionService.DeletionResult result =
                timelineDeletionTransactionService.detachEventItem(subjectId, targetEventId, itemId);

        assertThat(result).isEqualTo(new TimelineDeletionTransactionService.DeletionResult(1, 0, 0));
        assertThat(timelineEventItemRepository.findByTimelineEventId(targetEventId)).isEmpty();
        assertThat(timelineEventRepository.findById(targetEventId)).isPresent();
        assertThat(timelineItemRepository.findById(itemId)).isPresent();
        assertThat(findFixturePhotoDeleteJobs())
                .singleElement()
                .satisfies(job -> {
                    assertThat(job.getTimelineItemId()).isEqualTo(itemId);
                    assertThat(job.getObjectKey()).isEqualTo(PhotoObjectKeys.subjectFullKey(filename, subjectId));
                });
    }

    @Test
    void concurrentDetachOfSameJunction_oneSucceedsOtherIs404WithoutStaleStateError() throws Exception {
        Long eventId = saveEvent("같은 junction 동시 해제", 9);
        Long itemId = savePhotoLinkedTo(
                "raw-detach-dup", "0190b2c3-d4e5-7f6a-8b9c-0d1e2f3a4c04.jpg", 9, eventId);

        List<Outcome> outcomes = runConcurrently(
                () -> timelineDeletionTransactionService.detachEventItem(subjectId, eventId, itemId),
                () -> timelineDeletionTransactionService.detachEventItem(subjectId, eventId, itemId));

        // 직접 DELETE의 영향 행 수가 판정 기준이라 후발 요청은 500(stale-state)이 아니라 404 은닉으로 수렴한다.
        List<Outcome> succeeded = outcomes.stream().filter(outcome -> outcome.error() == null).toList();
        List<Outcome> failed = outcomes.stream().filter(outcome -> outcome.error() != null).toList();
        assertThat(succeeded).hasSize(1);
        assertThat(succeeded.getFirst().result())
                .isEqualTo(new TimelineDeletionTransactionService.DeletionResult(1, 0, 0));
        assertThat(failed).singleElement()
                .satisfies(outcome -> assertThat(outcome.error())
                        .isInstanceOfSatisfying(BusinessException.class,
                                exception -> assertThat(exception.getErrorCode()).isEqualTo(-404)));
        assertThat(timelineEventItemRepository.findByTimelineItemIdIn(List.of(itemId))).isEmpty();
        assertThat(timelineItemRepository.findById(itemId)).isPresent();
        assertThat(findFixturePhotoDeleteJobs()).hasSize(1);
    }

    /** 두 detach를 같은 순간 시작시키고 (결과, 예외) 쌍으로 수집한다 — 예외 타입 검증까지 테스트가 소유한다. */
    private List<Outcome> runConcurrently(
            Callable<TimelineDeletionTransactionService.DeletionResult> first,
            Callable<TimelineDeletionTransactionService.DeletionResult> second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Outcome>> futures = new ArrayList<>();
            for (Callable<TimelineDeletionTransactionService.DeletionResult> call : List.of(first, second)) {
                futures.add(executor.submit(() -> {
                    start.await();
                    try {
                        return new Outcome(call.call(), null);
                    } catch (Exception exception) {
                        return new Outcome(null, exception);
                    }
                }));
            }
            start.countDown();
            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> future : futures) {
                outcomes.add(future.get(30, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            executor.shutdownNow();
        }
    }

    private record Outcome(TimelineDeletionTransactionService.DeletionResult result, Exception error) {
    }
}
