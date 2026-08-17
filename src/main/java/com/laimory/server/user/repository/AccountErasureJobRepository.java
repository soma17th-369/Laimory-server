package com.laimory.server.user.repository;

import com.laimory.server.user.entity.AccountErasureJob;
import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** account_erasure_jobs 레포(#305) — userId-only PENDING enqueue만 소유한다. */
public interface AccountErasureJobRepository extends JpaRepository<AccountErasureJob, Long> {

    /**
     * 삭제 작업 insert-if-absent — {@code user_id} UNIQUE 중복은 원자적으로 no-op(0 반환)한다.
     * save 반복 중 unique 예외를 멱등성으로 catch하지 않기 위한 native 문장이다(탈퇴 transaction을
     * rollback-only로 오염시키지 않음 — term_agreements 선례). JPA auditing을 우회하므로 감사 컬럼은
     * 호출자가 캡처한 app 시각으로 직접 채운다({@code modified_by} NULL).
     */
    @Modifying
    @Transactional // REQUIRED — 탈퇴 transaction(UserWithdrawalTransactionService)에 합류한다
    @Query(value = """
            INSERT IGNORE INTO account_erasure_jobs (user_id, status, created_at, updated_at)
            VALUES (:userId, 'PENDING', :auditNow, :auditNow)
            """, nativeQuery = true)
    int insertIfAbsent(@Param("userId") Long userId, @Param("auditNow") LocalDateTime auditNow);
}
