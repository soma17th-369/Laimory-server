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
     * <p>native INSERT는 JPA auditing을 우회하므로 감사 timestamp를 직접 채운다. created_at은 처리 창,
     * updated_at은 같은 날 재선택 방지의 기준이라 service가 캡처한 하나의 KST 시각을 두 컬럼에 같이
     * 쓴다. 입력은 service에서 NOT NULL/길이/ASCII 계약을 먼저 검증하므로 IGNORE 대상은 두 UNIQUE
     * 충돌로 제한된다.
     */
    @Modifying
    @Transactional
    @Query(value = "insert ignore into timeline_photo_delete_jobs "
            + "(timeline_item_id, object_key, created_at, updated_at) "
            + "values (:timelineItemId, :objectKey, :auditAt, :auditAt)",
            nativeQuery = true)
    int insertIfAbsent(@Param("timelineItemId") long timelineItemId,
                       @Param("objectKey") String objectKey,
                       @Param("auditAt") LocalDateTime auditAt);

    /**
     * KST 생성일 D 기준 D+1~D+3 처리 창 안에서 오늘 아직 처리하지 않은 job을 claim 후보로 잠근다.
     * {@code updated_at < todayStart}가 PENDING의 같은 날 재선택과 활성 PROCESSING을 함께 거르므로,
     * 남는 PROCESSING은 전날 이전 claim이 남긴 stale 행이다.
     */
    @Query(value = "select * from timeline_photo_delete_jobs "
            + "where created_at >= :windowStart and created_at < :todayStart "
            + "and updated_at < :todayStart "
            + "and status in ('PENDING', 'PROCESSING') "
            + "order by created_at, timeline_photo_delete_job_id "
            + "limit :limit for update skip locked",
            nativeQuery = true)
    List<TimelinePhotoDeleteJob> findClaimableForUpdateSkipLocked(
            @Param("windowStart") LocalDateTime windowStart,
            @Param("todayStart") LocalDateTime todayStart,
            @Param("limit") int limit);

    /** claim한 행을 PROCESSING으로 바꾸고 같은 날 재선택을 막는 {@code updated_at}을 함께 갱신한다. */
    @Modifying
    @Query("update TimelinePhotoDeleteJob j "
            + "set j.status = :status, j.updatedAt = :claimedAt "
            + "where j.timelinePhotoDeleteJobId in :jobIds")
    int markProcessing(@Param("jobIds") Collection<Long> jobIds,
                       @Param("status") TimelinePhotoDeleteJobStatus status,
                       @Param("claimedAt") LocalDateTime claimedAt);

    /** 처리 창을 벗어나 재시도에서 제외된 미완료 job 수. 식별자·object key는 조회하지 않는다. */
    @Query("select count(j) from TimelinePhotoDeleteJob j where j.createdAt < :windowStart")
    long countCreatedBefore(@Param("windowStart") LocalDateTime windowStart);

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

    /**
     * 주어진 Item 중 job을 가진 Item ID를 <b>current read</b>로 조회한다(orphan 스위퍼 전용).
     *
     * <p>일반 SELECT는 {@code REPEATABLE READ}에서 transaction의 첫 consistent read가 고정한 snapshot을
     * 계속 읽는다. 스위퍼는 무잠금 탐색으로 transaction을 시작하므로, 그 뒤 다른 transaction이 commit한
     * job이 일반 SELECT에는 보이지 않는다. 반면 {@code insert ignore}는 write라 최신 상태를 보고 UNIQUE
     * 충돌로 0을 돌려준다 — 두 결과가 어긋나면 job 있는 Item을 지워 FK 위반으로 batch가 rollback된다.
     * {@code FOR SHARE}가 그 어긋남을 없앤다.
     */
    @Query(value = "select timeline_item_id from timeline_photo_delete_jobs "
            + "where timeline_item_id in (:itemIds) for share",
            nativeQuery = true)
    List<Long> findItemIdsWithJobForShare(@Param("itemIds") Collection<Long> itemIds);
}
