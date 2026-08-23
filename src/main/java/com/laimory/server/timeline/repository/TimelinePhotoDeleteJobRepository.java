package com.laimory.server.timeline.repository;

import com.laimory.server.timeline.TimelinePhotoDeleteJobStatus;
import com.laimory.server.timeline.entity.TimelinePhotoDeleteJob;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** timeline_photo_delete_jobs 저장소. 행과 원문 PHOTO Item이 S3 삭제 의무를 보존한다. */
public interface TimelinePhotoDeleteJobRepository extends JpaRepository<TimelinePhotoDeleteJob, Long> {

    /**
     * Item/object 중 하나라도 이미 enqueue됐다면 no-op하는 원자 insert.
     *
     * <p>native INSERT는 JPA auditing을 우회하므로 감사 timestamp를 직접 채운다. 입력은 service에서
     * NOT NULL/길이/ASCII 계약을 먼저 검증하므로 IGNORE 대상은 두 UNIQUE 충돌로 제한된다.
     */
    @Modifying
    @Transactional
    @Query(value = "insert ignore into timeline_photo_delete_jobs "
            + "(timeline_item_id, object_key, available_at, created_at, updated_at) "
            + "values (:timelineItemId, :objectKey, :availableAt, current_timestamp(6), current_timestamp(6))",
            nativeQuery = true)
    int insertIfAbsent(@Param("timelineItemId") long timelineItemId,
                       @Param("objectKey") String objectKey,
                       @Param("availableAt") LocalDateTime availableAt);

    @Query(value = "select * from timeline_photo_delete_jobs "
            + "where available_at <= :eligibleAt "
            + "order by available_at, created_at, timeline_photo_delete_job_id "
            + "limit :limit for update skip locked",
            nativeQuery = true)
    List<TimelinePhotoDeleteJob> findEligibleForUpdateSkipLocked(
            @Param("eligibleAt") LocalDateTime eligibleAt,
            @Param("limit") int limit);

    @Modifying
    @Query("update TimelinePhotoDeleteJob j "
            + "set j.status = :status, j.availableAt = :nextAvailableAt "
            + "where j.timelinePhotoDeleteJobId in :jobIds")
    int markProcessingUntil(@Param("jobIds") Collection<Long> jobIds,
                            @Param("status") TimelinePhotoDeleteJobStatus status,
                            @Param("nextAvailableAt") LocalDateTime nextAvailableAt);

    @Query(value = "select * from timeline_photo_delete_jobs "
            + "where object_key = :objectKey for update",
            nativeQuery = true)
    Optional<TimelinePhotoDeleteJob> findByObjectKeyForUpdate(@Param("objectKey") String objectKey);

    @Modifying
    @Query("update TimelinePhotoDeleteJob j set j.status = :pending "
            + "where j.timelinePhotoDeleteJobId in :jobIds and j.status = :processing")
    int markPending(@Param("jobIds") Collection<Long> jobIds,
                    @Param("pending") TimelinePhotoDeleteJobStatus pending,
                    @Param("processing") TimelinePhotoDeleteJobStatus processing);

    @Modifying
    @Transactional
    @Query("delete from TimelinePhotoDeleteJob j where j.timelinePhotoDeleteJobId in :jobIds")
    int deleteAllByJobIdIn(@Param("jobIds") Collection<Long> jobIds);
}
