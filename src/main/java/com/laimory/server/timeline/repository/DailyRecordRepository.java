package com.laimory.server.timeline.repository;

import com.laimory.server.timeline.entity.DailyRecord;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DailyRecordRepository extends JpaRepository<DailyRecord, Long> {

    Optional<DailyRecord> findByUserIdAndRecordDate(Long userId, LocalDate recordDate);

    List<DailyRecord> findByUserIdOrderByRecordDateDescDailyRecordIdDesc(Long userId);

    Optional<DailyRecord> findByDailyRecordIdAndUserId(Long dailyRecordId, Long userId);

    /**
     * 소유 DRAFT record만 SAVED로 옮기는 조건부 UPDATE. 영향 행 수가 전이 성공 판정 기준이라 같은
     * record에 저장 요청이 겹쳐도 정확히 하나만 1을 받는다 — read-then-write는 둘 다 통과해 AI 갱신
     * 결과가 서로를 덮는다. 조건 불일치(이미 SAVED·삭제됨·비소유)는 예외 대신 0으로 알려지고 호출부가
     * 원인을 분류한다.
     *
     * <p>bulk UPDATE는 영속성 컨텍스트와 JPA auditing을 우회하므로 {@code updated_at}을 직접 채운다
     * ({@code modified_by}는 NULL 유지 — 요청 주체는 access log가 기록. {@code user_memories} upsert와
     * 같은 규칙).
     */
    @Modifying
    @Query("update DailyRecord r "
            + "set r.status = com.laimory.server.timeline.DailyRecordStatus.SAVED, r.updatedAt = :now "
            + "where r.dailyRecordId = :dailyRecordId and r.userId = :userId "
            + "and r.status = com.laimory.server.timeline.DailyRecordStatus.DRAFT")
    int markSaved(@Param("dailyRecordId") Long dailyRecordId, @Param("userId") Long userId,
                  @Param("now") LocalDateTime now);
}
