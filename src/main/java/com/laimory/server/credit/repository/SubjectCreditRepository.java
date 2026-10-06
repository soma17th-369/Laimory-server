package com.laimory.server.credit.repository;

import com.laimory.server.credit.entity.SubjectCredit;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * subject_credits 레포. 신규 행 쓰기는 native insert-if-absent 한 문장이다(행을 만드는 것은 가입
 * transaction뿐이고 rollout backfill이 같은 의미의 운영 SQL을 쓴다). native INSERT는 JPA auditing을
 * 우회하므로 감사 timestamp를 직접 채운다.
 */
public interface SubjectCreditRepository extends JpaRepository<SubjectCredit, UUID> {

    @Modifying
    @Transactional
    @Query(value = "insert ignore into subject_credits (subject_id, remaining, created_at, updated_at) "
            + "values (:subjectId, :remaining, :now, :now)",
            nativeQuery = true)
    int insertIfAbsent(@Param("subjectId") String subjectId,
                       @Param("remaining") int remaining,
                       @Param("now") LocalDateTime now);

    /** PK 단건 조회 — 상속 {@code findById}와 달리 인터페이스 선언이라 transaction 없이 실행된다(#499). */
    Optional<SubjectCredit> findBySubjectId(UUID subjectId);

    /**
     * {@code amount}만큼 차감하되 0 아래로는 내려가지 않는다(잔액이 모자라면 0). 바닥 계산과 감소가 한 문장이라
     * 동시 차감도 음수가 되지 않는다. 0행은 잔액 0(또는 행 없음)이다.
     */
    @Modifying
    @Transactional
    @Query("update SubjectCredit c set c.remaining = greatest(c.remaining - :amount, 0) "
            + "where c.subjectId = :subjectId and c.remaining > 0")
    int deduct(@Param("subjectId") UUID subjectId, @Param("amount") int amount);

    /** 계정 삭제(#302)의 잔액 행 제거. 미존재는 0행(멱등). */
    @Modifying
    @Transactional
    @Query("delete from SubjectCredit c where c.subjectId = :subjectId")
    int deleteBySubjectId(@Param("subjectId") UUID subjectId);
}
