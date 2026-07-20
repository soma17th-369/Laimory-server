package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.laimory.server.timeline.TimelineEventType;
import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.entity.TimelineEvent;
import com.laimory.server.timeline.repository.DailyRecordRepository;
import com.laimory.server.timeline.repository.TimelineEventRepository;
import java.time.LocalDate;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Event 편집 API 2종(details PATCH ↔ memo PUT)의 교차-필드 lost update 회귀 검증(실 MySQL).
 *
 * <p>{@code TimelineEvent}의 {@code @DynamicUpdate}가 지키는 불변식: 두 트랜잭션이 같은 row를 읽고
 * 서로 다른 필드 그룹을 갱신해 순차 커밋해도, 나중 커밋이 상대의 변경을 자신의 로드 시점 스냅샷으로
 * 되돌리지 않는다. {@code @DynamicUpdate}가 없으면 Hibernate 기본 UPDATE가 모든 updatable 컬럼을
 * SET에 포함해 이 테스트는 실패해야 한다(B의 UPDATE가 A의 memo를 로드 시점 값 null로 덮어씀).
 *
 * 실행: docker compose up -d 후 ./gradlew integrationTest
 */
@SpringBootTest
@ActiveProfiles("docker")
@Tag("integration")
class TimelineEventEditConcurrencyIntegrationTest {

    // 콜백 통합 테스트의 고정 날짜(2000-01-01)와 (user_id, record_date) 유니크 충돌을 피한다.
    private static final LocalDate DATE = LocalDate.of(2000, 1, 2);
    private static final String ZONE = "Asia/Seoul";

    @Autowired
    private DailyRecordService dailyRecordService;
    @Autowired
    private DailyRecordRepository dailyRecordRepository;
    @Autowired
    private TimelineEventRepository timelineEventRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private Long eventId;

    @BeforeEach
    void setUp() {
        deleteFixtureRecord();
        DailyRecord record = dailyRecordRepository.save(
                DailyRecord.createDraft(0L, DATE, DATE.atTime(12, 0), ZONE));
        eventId = timelineEventRepository.save(
                        TimelineEvent.of(record.getDailyRecordId(), TimelineEventType.UNKNOWN, DATE.atTime(9, 0), null, "원래 제목", "원래 부제"))
                .getTimelineEventId();
    }

    @AfterEach
    void cleanUp() {
        deleteFixtureRecord();
    }

    private void deleteFixtureRecord() {
        dailyRecordService.findByUserIdAndRecordDate(0L, DATE)
                .ifPresent(record -> dailyRecordRepository.deleteById(record.getDailyRecordId())); // FK cascade로 이벤트도 삭제
    }

    @Test
    void concurrentMemoAndDetailsEdits_bothChangesSurvive() throws Exception {
        // 타임라인: 두 트랜잭션이 같은 event를 각자 로드(스냅샷 확보) → A가 memo 변경 커밋 →
        // B가 details 변경 커밋. 최종 상태에 A의 memo와 B의 details가 모두 남아야 한다.
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CountDownLatch bothLoaded = new CountDownLatch(2);
        CountDownLatch memoCommitted = new CountDownLatch(1);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> memoEditor = pool.submit(() -> { // memo PUT 시뮬레이션(서비스와 동일한 load→변경→dirty checking 커밋)
                tx.executeWithoutResult(status -> {
                    TimelineEvent event = timelineEventRepository.findById(eventId).orElseThrow();
                    bothLoaded.countDown();
                    await(bothLoaded); // B도 로드를 마친 뒤에만 커밋으로 진행(스냅샷 교차 보장)
                    event.updateMemo("A의 메모");
                }); // executeWithoutResult 반환 = 커밋 완료
                memoCommitted.countDown();
            });
            Future<?> detailsEditor = pool.submit(() -> { // details PATCH 시뮬레이션 — 마지막 커밋
                tx.executeWithoutResult(status -> {
                    TimelineEvent event = timelineEventRepository.findById(eventId).orElseThrow();
                    bothLoaded.countDown();
                    await(memoCommitted); // A의 커밋 이후에 자신의 변경을 커밋한다
                    event.updateDetails(TimelineEventType.UNKNOWN, "B의 제목", "B의 부제", DATE.atTime(10, 0), DATE.atTime(11, 0));
                });
            });
            memoEditor.get(30, TimeUnit.SECONDS);
            detailsEditor.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        TimelineEvent after = timelineEventRepository.findById(eventId).orElseThrow();
        assertThat(after.getMemo()).isEqualTo("A의 메모"); // @DynamicUpdate 부재 시 B의 UPDATE가 null로 되돌림
        assertThat(after.getTitle()).isEqualTo("B의 제목");
        assertThat(after.getSubtitle()).isEqualTo("B의 부제");
        assertThat(after.getStartAt()).isEqualTo(DATE.atTime(10, 0));
        assertThat(after.getEndAt()).isEqualTo(DATE.atTime(11, 0));
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).as("latch await timed out").isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while awaiting latch", e);
        }
    }
}
