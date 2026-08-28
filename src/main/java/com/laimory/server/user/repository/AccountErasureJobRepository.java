package com.laimory.server.user.repository;

import com.laimory.server.user.AccountErasureJobStatus;
import com.laimory.server.user.entity.AccountErasureJob;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * account_erasure_jobs 레포 — #305의 userId-only 접수와 #302 worker의 claim·전이·완료를 소유한다.
 *
 * <p>claim은 두 pass가 서로 다른 축을 쓴다. 정지는 접수 후 {@code quiesce-delay}가 지나면 곧바로
 * 대상이고(짧은 cron), 삭제는 PHOTO 삭제 job(#365)과 같은 <b>날짜 경계 처리 창</b>을 쓴다 —
 * {@code updated_at < todayStart}가 같은 날 재선택을 막으면서 재시도 간격을 겸하므로 삭제 pass에는
 * 별도 stale 기준이 없다.
 *
 * <p>모든 전이는 {@code (jobId, expectedStatus)} 조건부 UPDATE다. bulk UPDATE는 JPA auditing을
 * 우회하므로 {@code updated_at}을 직접 갱신한다(refresh_tokens·users 레포 선례).
 */
public interface AccountErasureJobRepository extends JpaRepository<AccountErasureJob, Long> {

    /**
     * 삭제 작업 insert-if-absent — {@code user_id} UNIQUE 중복은 원자적으로 no-op(0 반환)한다.
     * save 반복 중 unique 예외를 멱등성으로 catch하지 않기 위한 native 문장이다(탈퇴 transaction을
     * rollback-only로 오염시키지 않음 — term_agreements 선례). JPA auditing을 우회하므로 감사 컬럼은
     * 호출자가 캡처한 app 시각으로 직접 채운다({@code modified_by} NULL).
     *
     * <p>두 감사 컬럼에 같은 값을 넣는다 — {@code created_at}은 유예·처리 창의 기준이고
     * {@code updated_at}은 claim 표식이라, 한 번도 claim되지 않은 행에서 둘이 같아야 정지 자격이
     * 오직 {@code created_at + quiesce-delay}로만 결정된다.
     */
    @Modifying
    @Transactional // REQUIRED — 탈퇴 transaction(UserWithdrawalTransactionService)에 합류한다
    @Query(value = """
            INSERT IGNORE INTO account_erasure_jobs (user_id, status, created_at, updated_at)
            VALUES (:userId, 'PENDING', :auditNow, :auditNow)
            """, nativeQuery = true)
    int insertIfAbsent(@Param("userId") Long userId, @Param("auditNow") LocalDateTime auditNow);

    /**
     * 정지 대상 claim 후보를 잠근다 — 접수 후 {@code quiesce-delay}가 지났고 이번 stale 창에서 아직
     * 아무도 잡지 않은 {@code PENDING} 행이다. {@code eligibleBefore}는 {@code now - quiesce-delay},
     * {@code staleBefore}는 {@code now - stale-after}이며 properties가 {@code stale-after <=
     * quiesce-delay}를 강제하므로 실효 gate는 항상 {@code quiesce-delay}다.
     */
    @Query(value = "select * from account_erasure_jobs "
            + "where status = 'PENDING' "
            + "and created_at <= :eligibleBefore "
            + "and updated_at <= :staleBefore "
            + "order by created_at, account_erasure_job_id "
            + "limit :limit for update skip locked",
            nativeQuery = true)
    List<AccountErasureJob> findQuiesceClaimableForUpdateSkipLocked(
            @Param("eligibleBefore") LocalDateTime eligibleBefore,
            @Param("staleBefore") LocalDateTime staleBefore,
            @Param("limit") int limit);

    /**
     * 삭제 대상 claim 후보를 잠근다 — 접수일 D 기준 처리 창(기본 D+8~D+10) 안에서 오늘 아직 처리하지
     * 않은 {@code QUIESCED} 행이다. {@code windowStart}는 {@code T-(grace+window) 00:00},
     * {@code eligibleBefore}는 {@code T-grace 00:00}, {@code todayStart}는 {@code T 00:00}(KST)다.
     */
    @Query(value = "select * from account_erasure_jobs "
            + "where status = 'QUIESCED' "
            + "and created_at >= :windowStart and created_at < :eligibleBefore "
            + "and updated_at < :todayStart "
            + "order by created_at, account_erasure_job_id "
            + "limit :limit for update skip locked",
            nativeQuery = true)
    List<AccountErasureJob> findDeleteClaimableForUpdateSkipLocked(
            @Param("windowStart") LocalDateTime windowStart,
            @Param("eligibleBefore") LocalDateTime eligibleBefore,
            @Param("todayStart") LocalDateTime todayStart,
            @Param("limit") int limit);

    /**
     * claim 표식 — 선택한 행의 {@code updated_at}만 현재 시각으로 갱신해 같은 창에서의 재선택을 막는다.
     * status는 바꾸지 않는다(단계 전이는 작업이 실제로 끝난 뒤에만 일어난다).
     */
    @Modifying
    @Query("update AccountErasureJob j set j.updatedAt = :claimedAt "
            + "where j.accountErasureJobId in :jobIds")
    int markClaimed(@Param("jobIds") Collection<Long> jobIds, @Param("claimedAt") LocalDateTime claimedAt);

    /**
     * 조건부 단계 전이 — 기대 상태가 아니면 0행이고, 그건 실패가 아니라 다른 worker가 이미 처리했다는
     * 뜻이다. 호출자는 0행을 정상 종료로 다룬다.
     */
    @Modifying
    @Query("update AccountErasureJob j set j.status = :next, j.updatedAt = :at "
            + "where j.accountErasureJobId = :jobId and j.status = :expected")
    int transition(@Param("jobId") Long jobId,
                   @Param("expected") AccountErasureJobStatus expected,
                   @Param("next") AccountErasureJobStatus next,
                   @Param("at") LocalDateTime at);

    /**
     * 완료 — job 행을 지워 {@code users}를 향한 {@code ON DELETE RESTRICT}를 푼다. finalization
     * transaction 안에서 user 행 삭제보다 <b>먼저</b> 호출한다. 0행 = 다른 worker가 이미 완료.
     */
    @Modifying
    @Query("delete from AccountErasureJob j "
            + "where j.accountErasureJobId = :jobId and j.status = :expected")
    int deleteByIdAndStatus(@Param("jobId") Long jobId,
                            @Param("expected") AccountErasureJobStatus expected);

    /**
     * 처리 창을 벗어나 재시도에서 제외된 미완료 job 수. {@code MANUAL_REVIEW}는 자체 경보가 있으므로
     * 제외한다. userId·jobId는 조회하지 않는다 — 경보는 건수만 싣는다.
     */
    @Query("select count(j) from AccountErasureJob j "
            + "where j.createdAt < :windowStart and j.status <> :manualReview")
    long countExpired(@Param("windowStart") LocalDateTime windowStart,
                      @Param("manualReview") AccountErasureJobStatus manualReview);

    /** 사람이 봐야 하는 실패 건수. 만료와 같은 형태로 건수만 경보한다. */
    long countByStatus(AccountErasureJobStatus status);
}
