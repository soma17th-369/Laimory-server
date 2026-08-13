package com.laimory.server.timeline.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.timeline.ItemType;
import com.laimory.server.timeline.entity.TimelineItem;
import com.laimory.server.timeline.entity.TimelinePhotoDeleteJob;
import com.laimory.server.timeline.payload.PhotoPayload;
import com.laimory.server.timeline.service.TimelinePhotoDeleteJobService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDateTime;
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
 * <p>실행: docker compose up -d 후 ./gradlew integrationTest
 */
@SpringBootTest
@ActiveProfiles("docker")
@Tag("integration")
@Transactional
class TimelinePhotoDeleteJobPersistenceIntegrationTest {

    private static final LocalDateTime OLDEST = LocalDateTime.of(2026, 7, 26, 10, 0);
    private static final LocalDateTime NEWEST = LocalDateTime.of(2026, 7, 26, 12, 0);

    @Autowired
    private TimelinePhotoDeleteJobService service;

    @Autowired
    private TimelinePhotoDeleteJobRepository repository;

    @Autowired
    private TimelineItemRepository timelineItemRepository;

    @PersistenceContext
    private EntityManager em;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void clearJobs() {
        repository.deleteAllInBatch();
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
        assertThat(saved.getAvailableAt()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
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
                "available_at",
                "created_at",
                "updated_at",
                "modified_by");

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

        assertThat(service.countPending()).isEqualTo(1);
        TimelinePhotoDeleteJob saved = repository.findAll().getFirst();
        assertThat(saved.getTimelineItemId()).isEqualTo(firstItemId);
        assertThat(saved.getObjectKey()).isEqualTo("user-hash/photos/first.jpg");
    }

    @Test
    void claimEligible_ordersByCreatedAtThenId_andReportsQueueSummary() {
        long thirdItemId = savePhotoItem("three");
        long firstItemId = savePhotoItem("oldest-first");
        long secondItemId = savePhotoItem("oldest-second");
        service.insertIfAbsent(thirdItemId, "user-hash/photos/three.jpg");
        service.insertIfAbsent(firstItemId, "user-hash/photos/one.jpg");
        service.insertIfAbsent(secondItemId, "user-hash/photos/two.jpg");

        List<TimelinePhotoDeleteJob> inserted = repository.findAll();
        long newestJobId = idForItem(inserted, thirdItemId);
        long oldestFirstJobId = idForItem(inserted, firstItemId);
        long oldestSecondJobId = idForItem(inserted, secondItemId);
        setCreatedAt(newestJobId, NEWEST);
        setCreatedAt(oldestFirstJobId, OLDEST);
        setCreatedAt(oldestSecondJobId, OLDEST);
        setAvailableAt(newestJobId, OLDEST.minusDays(1));
        setAvailableAt(oldestFirstJobId, OLDEST.minusDays(1));
        setAvailableAt(oldestSecondJobId, OLDEST.minusDays(1));
        em.flush();
        em.clear();

        assertThat(service.claimEligible(2))
                .extracting(TimelinePhotoDeleteJob::getTimelineItemId)
                .containsExactly(firstItemId, secondItemId);
        assertThat(service.countPending()).isEqualTo(3);
        assertThat(service.findOldestCreatedAt()).contains(OLDEST);
    }

    @Test
    void deleteSucceeded_removesOnlyGivenRows_andEmptyInputIsNoOp() {
        long deleteItemId = savePhotoItem("delete");
        long keepItemId = savePhotoItem("keep");
        service.insertIfAbsent(deleteItemId, "user-hash/photos/delete.jpg");
        service.insertIfAbsent(keepItemId, "user-hash/photos/keep.jpg");

        List<TimelinePhotoDeleteJob> inserted = repository.findAll();
        long succeededId = idForItem(inserted, deleteItemId);

        assertThat(service.deleteSucceeded(List.of())).isZero();
        assertThat(service.deleteSucceeded(List.of(succeededId, Long.MAX_VALUE))).isEqualTo(1);
        assertThat(service.countPending()).isEqualTo(1);
        assertThat(repository.findAll().getFirst().getTimelineItemId()).isEqualTo(keepItemId);
        assertThat(timelineItemRepository.findById(deleteItemId)).isPresent();
    }

    @Test
    void emptyQueue_hasZeroCountAndNoOldestTimestamp() {
        assertThat(service.countPending()).isZero();
        assertThat(service.findOldestCreatedAt()).isEmpty();
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
                OLDEST,
                null,
                objectMapper.valueToTree(new PhotoPayload(
                        filename,
                        "content://fixture/" + rawSuffix,
                        null,
                        null,
                        null,
                        "https://cdn.example/" + filename)));
        return timelineItemRepository.save(item).getTimelineItemId();
    }

    private void setCreatedAt(long jobId, LocalDateTime createdAt) {
        em.createNativeQuery("""
                        update timeline_photo_delete_jobs
                        set created_at = :createdAt
                        where timeline_photo_delete_job_id = :jobId
                        """)
                .setParameter("createdAt", createdAt)
                .setParameter("jobId", jobId)
                .executeUpdate();
    }

    private void setAvailableAt(long jobId, LocalDateTime availableAt) {
        em.createNativeQuery("""
                        update timeline_photo_delete_jobs
                        set available_at = :availableAt
                        where timeline_photo_delete_job_id = :jobId
                        """)
                .setParameter("availableAt", availableAt)
                .setParameter("jobId", jobId)
                .executeUpdate();
    }
}
