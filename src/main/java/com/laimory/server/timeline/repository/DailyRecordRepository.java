package com.laimory.server.timeline.repository;

import com.laimory.server.timeline.EmotionType;
import com.laimory.server.timeline.entity.DailyRecord;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DailyRecordRepository extends JpaRepository<DailyRecord, Long> {

    Optional<DailyRecord> findBySubjectIdAndRecordDate(UUID subjectId, LocalDate recordDate);

    List<DailyRecord> findBySubjectIdOrderByRecordDateDescDailyRecordIdDesc(UUID subjectId);

    Optional<DailyRecord> findByDailyRecordIdAndSubjectId(Long dailyRecordId, UUID subjectId);

    /**
     * 소유 record만 골라 {@code record_date} 오름차순으로 반환한다. 없는 id는 결과에서 빠지므로,
     * 요청 목록과의 차집합이 곧 "그 사이 삭제된 하루"다.
     *
     * <p>정렬을 DB가 하는 이유: User Memory 갱신은 접기라 <b>기록 날짜 순서로</b> 접어야 한다.
     * 큐 진입 순서(과거 날짜를 나중에 저장할 수 있다)와 다르다.
     */
    List<DailyRecord> findBySubjectIdAndDailyRecordIdInOrderByRecordDateAsc(
            UUID subjectId, Collection<Long> dailyRecordIds);

    /**
     * 소유 DRAFT record만 요청 감정과 함께 SAVED로 옮기는 조건부 UPDATE. 영향 행 수가 전이 성공 판정
     * 기준이라 같은 record에 저장 요청이 겹쳐도 정확히 하나만 1을 받는다 — read-then-write는 둘 다 통과해
     * AI 갱신 결과가 서로를 덮는다. 조건 불일치(이미 SAVED·삭제됨·비소유)는 예외 대신 0으로 알려지고
     * 호출부가 원인을 분류한다. 감정과 상태의 write 지점이 이 UPDATE 하나뿐이라 둘은 항상 함께 확정된다.
     *
     * <p>bulk UPDATE는 영속성 컨텍스트와 JPA auditing을 우회하므로 {@code updated_at}을 직접 채운다
     * ({@code modified_by}는 NULL 유지 — 요청 주체는 access log가 기록. {@code user_memories} upsert와
     * 같은 규칙).
     */
    @Modifying
    @Query("update DailyRecord r "
            + "set r.emotionType = :emotionType, "
            + "r.status = com.laimory.server.timeline.DailyRecordStatus.SAVED, r.updatedAt = :now "
            + "where r.dailyRecordId = :dailyRecordId and r.subjectId = :subjectId "
            + "and r.status = com.laimory.server.timeline.DailyRecordStatus.DRAFT")
    int markSaved(@Param("dailyRecordId") Long dailyRecordId, @Param("subjectId") UUID subjectId,
                  @Param("emotionType") EmotionType emotionType, @Param("now") LocalDateTime now);
}
