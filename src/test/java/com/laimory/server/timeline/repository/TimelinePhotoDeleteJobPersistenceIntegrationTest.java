package com.laimory.server.timeline.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.timeline.ItemType;
import com.laimory.server.timeline.TimelinePhotoDeleteJobStatus;
import com.laimory.server.timeline.entity.TimelineItem;
import com.laimory.server.timeline.entity.TimelinePhotoDeleteJob;
import com.laimory.server.timeline.payload.PhotoPayload;
import com.laimory.server.timeline.service.TimelinePhotoDeleteJobService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * timeline_photo_delete_jobs의 실 MySQL 저장 계약 검증.
 *
 * <p>claim 창(D+1~D+3)·stale 판정이 실제 현재 KST 날짜를 기준으로 하므로 fixture 날짜는 app Clock에서
 * 계산한 오늘 00:00의 상대 시각으로 만든다. fixture 값은 KST 벽시계 리터럴이다 — #371 이후 앱 바인딩이
 * 항등이라 Hibernate native parameter로 넣어도 앱 writer와 같은 프레임이 된다.
 *
 * <p>실행: docker compose up -d 후 ./gradlew integrationTest
 */
@SpringBootTest
@ActiveProfiles("docker")
@Tag("integration")
@Transactional
class TimelinePhotoDeleteJobPersistenceIntegrationTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Autowired
    private TimelinePhotoDeleteJobService service;

    @Autowired
    private TimelinePhotoDeleteJobRepository repository;

    @Autowired
    private TimelineItemRepository timelineItemRepository;

    @Autowired
    private Clock clock;

    @PersistenceContext
    private EntityManager em;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private LocalDateTime todayStart;

    @BeforeEach
    void setUp() {
        repository.deleteAllInBatch();
        todayStart = ZonedDateTime.ofInstant(clock.instant(), SEOUL).toLocalDate().atStartOfDay();
    }

    @Test
    void insertIfAbsent_persistsOriginalItemReferenceAndAuditFields_withRestrictForeignKey() {
        long itemId = savePhotoItem("one");
        assertThat(service.insertIfAbsent(itemId, "user-hash/photos/one.jpg")).isTrue();

        em.flush();
        em.clear();

        TimelinePhotoDeleteJob saved = repository.findAll().getFirst();
        assertThat(saved.getTimelineItemId()).isEqualTo(itemId);
        assertThat(saved.getObjectKey()).isEqualTo("user-hash/photos/one.jpg");
        assertThat(saved.getStatus()).isEqualTo(TimelinePhotoDeleteJobStatus.PENDING);
        // 처리 창(created_at)과 같은 날 재선택 방지(updated_at)의 기준이 같도록 한 시각을 같이 쓴다.
        assertThat(saved.getCreatedAt()).isNotNull().isEqualTo(saved.getUpdatedAt());
        assertThat(saved.getModifiedBy()).isNull();

        List<String> columns = em.createNativeQuery("""
                        select column_name
                        from information_schema.columns
                        where table_schema = database()
                          and table_name = 'timeline_photo_delete_jobs'
                        order by ordinal_position
                        """)
                .getResultList().stream()
                .map(Object::toString)
                .toList();
        assertThat(columns).containsExactly(
                "timeline_photo_delete_job_id",
                "timeline_item_id",
                "object_key",
                "status",
                "created_at",
                "updated_at",
                "modified_by");

        List<String> indexes = em.createNativeQuery("""
                        select distinct index_name
                        from information_schema.statistics
                        where table_schema = database()
                          and table_name = 'timeline_photo_delete_jobs'
                        """)
                .getResultList().stream()
                .map(Object::toString)
                .toList();
        assertThat(indexes).containsExactlyInAnyOrder(
                "PRIMARY",
                "uq_timeline_photo_delete_jobs_item",
                "uq_timeline_photo_delete_jobs_object",
                "idx_timeline_photo_delete_jobs_claim");

        List<String> foreignKeys = em.createNativeQuery("""
                        select constraint_name
                        from information_schema.table_constraints
                        where table_schema = database()
                          and table_name = 'timeline_photo_delete_jobs'
                          and constraint_type = 'FOREIGN KEY'
                        """)
                .getResultList().stream()
                .map(Object::toString)
                .toList();
        assertThat(foreignKeys).containsExactly("fk_timeline_photo_delete_jobs_item");
    }

    @Test
    void insertIfAbsent_keepsOneRow_forDuplicateItemOrObject() {
        long firstItemId = savePhotoItem("first");
        long secondItemId = savePhotoItem("second");
        assertThat(service.insertIfAbsent(firstItemId, "user-hash/photos/first.jpg")).isTrue();

        assertThat(service.insertIfAbsent(firstItemId, "user-hash/photos/other.jpg")).isFalse();
        assertThat(service.insertIfAbsent(secondItemId, "user-hash/photos/first.jpg")).isFalse();

        assertThat(repository.count()).isEqualTo(1);
        TimelinePhotoDeleteJob saved = repository.findAll().getFirst();
        assertThat(saved.getTimelineItemId()).isEqualTo(firstItemId);
        assertThat(saved.getObjectKey()).isEqualTo("user-hash/photos/first.jpg");
    }

    @Test
    void claimEligible_ordersByCreatedAtThenId() {
        long thirdItemId = savePhotoItem("three");
        long firstItemId = savePhotoItem("oldest-first");
        long secondItemId = savePhotoItem("oldest-second");
        service.insertIfAbsent(thirdItemId, "user-hash/photos/three.jpg");
        service.insertIfAbsent(firstItemId, "user-hash/photos/one.jpg");
        service.insertIfAbsent(secondItemId, "user-hash/photos/two.jpg");

        LocalDateTime oldest = todayStart.minusDays(2);
        LocalDateTime newest = todayStart.minusDays(1);
        List<TimelinePhotoDeleteJob> inserted = repository.findAll();
        long newestJobId = idForItem(inserted, thirdItemId);
        long oldestFirstJobId = idForItem(inserted, firstItemId);
        long oldestSecondJobId = idForItem(inserted, secondItemId);
        setAuditTimes(newestJobId, newest, newest);
        setAuditTimes(oldestFirstJobId, oldest, oldest);
        setAuditTimes(oldestSecondJobId, oldest, oldest);
        em.flush();
        em.clear();

        assertThat(service.claimEligible(2))
                .extracting(TimelinePhotoDeleteJob::getTimelineItemId)
                .containsExactly(firstItemId, secondItemId);
        em.flush();
        em.clear();
        assertThat(repository.findAllById(List.of(oldestFirstJobId, oldestSecondJobId)))
                .allSatisfy(job -> {
                    assertThat(job.getStatus()).isEqualTo(TimelinePhotoDeleteJobStatus.PROCESSING);
                    // claim이 updated_at을 오늘 시각으로 갱신해 같은 날 재선택을 막는다.
                    assertThat(job.getUpdatedAt()).isAfterOrEqualTo(todayStart);
                });
        assertThat(repository.count()).isEqualTo(3);
    }

    @Test
    void claimEligible_includesWindowBoundaries_andSkipsExpiredAndCreatedToday() {
        long dPlusOneItemId = savePhotoItem("d1");
        long dPlusThreeItemId = savePhotoItem("d3");
        long expiredItemId = savePhotoItem("expired");
        long todayItemId = savePhotoItem("today");
        service.insertIfAbsent(dPlusOneItemId, "user-hash/photos/d1.jpg");
        service.insertIfAbsent(dPlusThreeItemId, "user-hash/photos/d3.jpg");
        service.insertIfAbsent(expiredItemId, "user-hash/photos/expired.jpg");
        service.insertIfAbsent(todayItemId, "user-hash/photos/today.jpg");

        List<TimelinePhotoDeleteJob> inserted = repository.findAll();
        // 생성일 D의 처리 기회는 D+1~D+3이다: 오늘이 D+1인 어제 행과 창 시작 경계(오늘이 D+3)의 행만 대상이다.
        LocalDateTime dPlusOneCreated = todayStart.minusHours(12);
        LocalDateTime windowStart = todayStart.minusDays(3);
        LocalDateTime expiredCreated = windowStart.minusSeconds(1);
        LocalDateTime todayCreated = todayStart.plusSeconds(1);
        setAuditTimes(idForItem(inserted, dPlusOneItemId), dPlusOneCreated, dPlusOneCreated);
        setAuditTimes(idForItem(inserted, dPlusThreeItemId), windowStart, windowStart);
        setAuditTimes(idForItem(inserted, expiredItemId), expiredCreated, expiredCreated);
        setAuditTimes(idForItem(inserted, todayItemId), todayCreated, todayCreated);
        em.flush();
        em.clear();

        assertThat(service.claimEligible(100))
                .extracting(TimelinePhotoDeleteJob::getTimelineItemId)
                .containsExactly(dPlusThreeItemId, dPlusOneItemId);
        em.flush();
        em.clear();
        assertThat(repository.findAll())
                .filteredOn(job -> job.getTimelineItemId() == expiredItemId
                        || job.getTimelineItemId() == todayItemId)
                .extracting(TimelinePhotoDeleteJob::getStatus)
                .containsOnly(TimelinePhotoDeleteJobStatus.PENDING);
    }

    @Test
    void claimEligible_skipsRowsTouchedToday_andReclaimsOnlyStaleProcessing() {
        long touchedTodayItemId = savePhotoItem("touched");
        long staleItemId = savePhotoItem("stale");
        long activeItemId = savePhotoItem("active");
        service.insertIfAbsent(touchedTodayItemId, "user-hash/photos/touched.jpg");
        service.insertIfAbsent(staleItemId, "user-hash/photos/stale.jpg");
        service.insertIfAbsent(activeItemId, "user-hash/photos/active.jpg");

        List<TimelinePhotoDeleteJob> inserted = repository.findAll();
        LocalDateTime withinWindow = todayStart.minusDays(2);
        long touchedTodayJobId = idForItem(inserted, touchedTodayItemId);
        long staleJobId = idForItem(inserted, staleItemId);
        long activeJobId = idForItem(inserted, activeItemId);
        // 오늘 이미 실패 복귀한 PENDING: updated_at이 오늘이라 이번 run에서 재선택되지 않는다.
        setAuditTimes(touchedTodayJobId, withinWindow, todayStart.plusSeconds(1));
        // 전날 claim이 crash로 남긴 stale PROCESSING: 다음 일일 실행이 재선점한다.
        setAuditTimes(staleJobId, withinWindow, todayStart.minusHours(1));
        setStatus(staleJobId, TimelinePhotoDeleteJobStatus.PROCESSING);
        // 오늘 다른 worker가 claim한 active PROCESSING은 건드리지 않는다.
        setAuditTimes(activeJobId, withinWindow, todayStart.plusSeconds(1));
        setStatus(activeJobId, TimelinePhotoDeleteJobStatus.PROCESSING);
        em.flush();
        em.clear();

        assertThat(service.claimEligible(100))
                .extracting(TimelinePhotoDeleteJob::getTimelineItemId)
                .containsExactly(staleItemId);
    }

    @Test
    void countExpired_countsOnlyUnfinishedRowsOlderThanWindow() {
        long expiredPendingItemId = savePhotoItem("expired-pending");
        long expiredProcessingItemId = savePhotoItem("expired-processing");
        long withinWindowItemId = savePhotoItem("within");
        service.insertIfAbsent(expiredPendingItemId, "user-hash/photos/expired-pending.jpg");
        service.insertIfAbsent(expiredProcessingItemId, "user-hash/photos/expired-processing.jpg");
        service.insertIfAbsent(withinWindowItemId, "user-hash/photos/within.jpg");

        List<TimelinePhotoDeleteJob> inserted = repository.findAll();
        LocalDateTime expiredCreated = todayStart.minusDays(3).minusSeconds(1);
        LocalDateTime withinCreated = todayStart.minusDays(1);
        long expiredProcessingJobId = idForItem(inserted, expiredProcessingItemId);
        setAuditTimes(idForItem(inserted, expiredPendingItemId), expiredCreated, expiredCreated);
        setAuditTimes(expiredProcessingJobId, expiredCreated, expiredCreated);
        setStatus(expiredProcessingJobId, TimelinePhotoDeleteJobStatus.PROCESSING);
        setAuditTimes(idForItem(inserted, withinWindowItemId), withinCreated, withinCreated);
        em.flush();
        em.clear();

        assertThat(service.countExpired()).isEqualTo(2);
    }

    @Test
    void deleteByIds_removesOnlyGivenRows_andEmptyInputIsNoOp() {
        long deleteItemId = savePhotoItem("delete");
        long keepItemId = savePhotoItem("keep");
        service.insertIfAbsent(deleteItemId, "user-hash/photos/delete.jpg");
        service.insertIfAbsent(keepItemId, "user-hash/photos/keep.jpg");

        List<TimelinePhotoDeleteJob> inserted = repository.findAll();
        long succeededId = idForItem(inserted, deleteItemId);

        assertThat(service.deleteByIds(List.of())).isZero();
        assertThat(service.deleteByIds(List.of(succeededId, Long.MAX_VALUE))).isEqualTo(1);
        assertThat(repository.count()).isEqualTo(1);
        assertThat(repository.findAll().getFirst().getTimelineItemId()).isEqualTo(keepItemId);
        assertThat(timelineItemRepository.findById(deleteItemId)).isPresent();
    }

    private long idForItem(List<TimelinePhotoDeleteJob> jobs, long timelineItemId) {
        return jobs.stream()
                .filter(job -> job.getTimelineItemId() == timelineItemId)
                .map(TimelinePhotoDeleteJob::getTimelinePhotoDeleteJobId)
                .findFirst()
                .orElseThrow();
    }

    private long savePhotoItem(String rawSuffix) {
        String filename = rawSuffix + ".jpg";
        TimelineItem item = TimelineItem.of(
                ItemType.PHOTO,
                "raw-" + rawSuffix,
                todayStart.minusDays(1),
                null,
                objectMapper.valueToTree(new PhotoPayload(
                        filename,
                        "content://fixture/" + rawSuffix,
                        null,
                        null,
                        null,
                        null, null,
                        "https://cdn.example/" + filename)));
        return timelineItemRepository.save(item).getTimelineItemId();
    }

    private void setAuditTimes(long jobId, LocalDateTime createdAt, LocalDateTime updatedAt) {
        em.createNativeQuery("""
                        update timeline_photo_delete_jobs
                        set created_at = :createdAt, updated_at = :updatedAt
                        where timeline_photo_delete_job_id = :jobId
                        """)
                .setParameter("createdAt", createdAt)
                .setParameter("updatedAt", updatedAt)
                .setParameter("jobId", jobId)
                .executeUpdate();
    }

    private void setStatus(long jobId, TimelinePhotoDeleteJobStatus status) {
        em.createNativeQuery("""
                        update timeline_photo_delete_jobs
                        set status = :status
                        where timeline_photo_delete_job_id = :jobId
                        """)
                .setParameter("status", status.name())
                .setParameter("jobId", jobId)
                .executeUpdate();
    }
}
