package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.common.error.BusinessException;
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
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Event-Item 연결 해제 ↔ 실 MySQL 계약 검증(mockito론 못 잡음): junction 한 줄만 지워지고 shared Item은
 * 유지되며, 마지막 참조 해제는 PHOTO Item/job을 보존한다. 동시 해제는 Item 행 잠금 + current-read 잠금
 * 판정으로 직렬화되어 — 공유 PHOTO의 두 연결을 동시에 끊어도 job이 정확히 1개 생기고, 같은 junction을
 * 두 요청이 끊으면 하나만 성공하고 후발은 404다(stale-state 500 없음).
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

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Set<Long> fixtureItemIds = new HashSet<>();

    private long userId;
    private Long recordId;

    @BeforeEach
    void setUp() {
        userId = Math.abs(UUID.randomUUID().getLeastSignificantBits() % 1_000_000_000L);
        fixtureItemIds.clear();
        recordId = dailyRecordRepository.save(DailyRecord.createDraft(userId, DATE, DATE.atTime(12, 0), ZONE))
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
        dailyRecordRepository.findByUserIdAndRecordDate(userId, DATE)
                .ifPresent(record -> dailyRecordRepository.deleteById(record.getDailyRecordId()));
    }

    private List<TimelinePhotoDeleteJob> findFixturePhotoDeleteJobs() {
        return timelinePhotoDeleteJobRepository.findAll().stream()
                .filter(job -> fixtureItemIds.contains(job.getTimelineItemId()))
                .toList();
    }

    private Long saveEvent(String title, int hour) {
        return timelineEventRepository.save(
                        TimelineEvent.of(recordId, TimelineEventType.UNKNOWN, DATE.atTime(hour, 0), null, title, null))
                .getTimelineEventId();
    }

    private Long savePhotoLinkedTo(String rawId, String filename, int hour, Long... eventIds) {
        PhotoPayload payload = new PhotoPayload(
                filename, "content://fixture/" + rawId, null, null, null,
                "https://cdn.example/" + PhotoObjectKeys.fullKey(filename, userId));
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
                timelineDeletionTransactionService.detachEventItem(userId, targetEventId, itemId);

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
                timelineDeletionTransactionService.detachEventItem(userId, targetEventId, itemId);

        assertThat(result).isEqualTo(new TimelineDeletionTransactionService.DeletionResult(1, 0, 0));
        assertThat(timelineEventItemRepository.findByTimelineEventId(targetEventId)).isEmpty();
        assertThat(timelineEventRepository.findById(targetEventId)).isPresent();
        assertThat(timelineItemRepository.findById(itemId)).isPresent();
        assertThat(findFixturePhotoDeleteJobs())
                .singleElement()
                .satisfies(job -> {
                    assertThat(job.getTimelineItemId()).isEqualTo(itemId);
                    assertThat(job.getObjectKey()).isEqualTo(PhotoObjectKeys.fullKey(filename, userId));
                });
    }

    @Test
    void concurrentDetachOfSharedPhoto_createsExactlyOneJob() throws Exception {
        Long eventA = saveEvent("동시 해제 A", 9);
        Long eventB = saveEvent("동시 해제 B", 10);
        Long itemId = savePhotoLinkedTo(
                "raw-detach-race", "0190b2c3-d4e5-7f6a-8b9c-0d1e2f3a4c03.jpg",
                9, eventA, eventB);

        List<Outcome> outcomes = runConcurrently(
                () -> timelineDeletionTransactionService.detachEventItem(userId, eventA, itemId),
                () -> timelineDeletionTransactionService.detachEventItem(userId, eventB, itemId));

        // 서로 다른 junction을 끊는 두 요청은 모두 성공하되, 마지막 참조 판정은 정확히 한 번만 참이어야 한다.
        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome.error()).isNull());
        assertThat(outcomes).extracting(Outcome::result)
                .containsExactlyInAnyOrder(
                        new TimelineDeletionTransactionService.DeletionResult(0, 1, 0),
                        new TimelineDeletionTransactionService.DeletionResult(1, 0, 0));
        assertThat(timelineEventItemRepository.findByTimelineItemIdIn(List.of(itemId))).isEmpty();
        assertThat(timelineItemRepository.findById(itemId)).isPresent();
        assertThat(findFixturePhotoDeleteJobs()).hasSize(1);
    }

    @Test
    void concurrentDetachOfSameJunction_oneSucceedsOtherIs404WithoutStaleStateError() throws Exception {
        Long eventId = saveEvent("같은 junction 동시 해제", 9);
        Long itemId = savePhotoLinkedTo(
                "raw-detach-dup", "0190b2c3-d4e5-7f6a-8b9c-0d1e2f3a4c04.jpg", 9, eventId);

        List<Outcome> outcomes = runConcurrently(
                () -> timelineDeletionTransactionService.detachEventItem(userId, eventId, itemId),
                () -> timelineDeletionTransactionService.detachEventItem(userId, eventId, itemId));

        // 잠금 조회가 target 존재의 권위라 후발 요청은 500(stale-state)이 아니라 404 은닉으로 수렴한다.
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
