package com.laimory.server.timeline.repository;

import com.laimory.server.timeline.DailyRecordStatus;
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
import org.springframework.transaction.annotation.Transactional;

/**
 * 앱 조회 메서드는 이름에 {@code DeletedAtIsNull}을 담아 활성 행(soft delete 안 된 행)만 읽는다(#568).
 * 삭제 행까지 보는 메서드는 계정 삭제(#302)용 {@link #findIdsBySubjectIdAfterId}·{@link #deleteAllByIdIn}뿐이다.
 */
public interface DailyRecordRepository extends JpaRepository<DailyRecord, Long> {

    Optional<DailyRecord> findBySubjectIdAndRecordDateAndDeletedAtIsNull(UUID subjectId, LocalDate recordDate);

    /** PK 단건 조회 — 상속 {@code findById}와 달리 인터페이스 선언이라 transaction 없이 실행된다(#499). */
    Optional<DailyRecord> findByDailyRecordIdAndDeletedAtIsNull(Long dailyRecordId);

    List<DailyRecord> findBySubjectIdAndDeletedAtIsNullOrderByRecordDateDescDailyRecordIdDesc(UUID subjectId);

    Optional<DailyRecord> findByDailyRecordIdAndSubjectIdAndDeletedAtIsNull(Long dailyRecordId, UUID subjectId);

    /**
     * subject의 record id만 유계로 읽는다(#302 정지 단계) — User Memory 미반영 큐의 member가
     * {@code {subject}:{recordId}} 형식이라 큐를 비우려면 id가 필요하다. 엔티티를 통째로 싣지 않으려고
     * projection으로 뽑는다.
     *
     * <p>{@code (subject_id, record_date)} UNIQUE의 leftmost prefix를 타며, PK 오름차순 + 커서로
     * 페이지를 넘긴다 — 기록이 많은 회원에서도 한 번에 다 읽지 않는다.
     */
    /**
     * 계정 삭제(#302)의 record 일괄 제거 — Event와 junction은 FK CASCADE로 함께 사라진다.
     * 같은 batch의 Item 삭제와 <b>한 transaction</b>이어야 한다(Item을 특정할 경로가 사라지기 때문).
     */
    @Modifying
    @Query("delete from DailyRecord r where r.dailyRecordId in :dailyRecordIds")
    int deleteAllByIdIn(@Param("dailyRecordIds") Collection<Long> dailyRecordIds);

    @Query("select r.dailyRecordId from DailyRecord r "
            + "where r.subjectId = :subjectId and r.dailyRecordId > :afterId "
            + "order by r.dailyRecordId")
    List<Long> findIdsBySubjectIdAfterId(@Param("subjectId") UUID subjectId,
                                         @Param("afterId") Long afterId,
                                         org.springframework.data.domain.Pageable pageable);

    /**
     * 소유 record만 골라 {@code record_date} 오름차순으로 반환한다. 없는 id는 결과에서 빠지므로,
     * 요청 목록과의 차집합이 곧 "그 사이 삭제된 하루"다.
     *
     * <p>정렬을 DB가 하는 이유: User Memory 갱신은 접기라 <b>기록 날짜 순서로</b> 접어야 한다.
     * 큐 진입 순서(과거 날짜를 나중에 저장할 수 있다)와 다르다.
     */
    List<DailyRecord> findBySubjectIdAndDailyRecordIdInAndDeletedAtIsNullOrderByRecordDateAsc(
            UUID subjectId, Collection<Long> dailyRecordIds);

    /**
     * 소유 record를 양끝 포함 날짜 범위로 골라 {@code record_date} 오름차순으로 반환한다.
     * 월별 경량 조회가 월 첫날~마지막 날 범위로 호출하며 활성 행은 {@code (subject_id, record_date)}가 유일해
     * 추가 tie-breaker는 필요 없다.
     */
    List<DailyRecord>
            findBySubjectIdAndRecordDateGreaterThanEqualAndRecordDateLessThanEqualAndDeletedAtIsNullOrderByRecordDateAsc(
            UUID subjectId, LocalDate startInclusive, LocalDate endInclusive);

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
    @Transactional
    @Query("update DailyRecord r "
            + "set r.emotionType = :emotionType, "
            + "r.status = :saved, r.updatedAt = :now "
            + "where r.dailyRecordId = :dailyRecordId and r.subjectId = :subjectId "
            + "and r.status = :draft and r.deletedAt is null")
    int markSaved(@Param("dailyRecordId") Long dailyRecordId, @Param("subjectId") UUID subjectId,
                  @Param("emotionType") EmotionType emotionType, @Param("now") LocalDateTime now,
                  @Param("saved") DailyRecordStatus saved, @Param("draft") DailyRecordStatus draft);

    default int markSaved(Long dailyRecordId, UUID subjectId, EmotionType emotionType, LocalDateTime now) {
        return markSaved(dailyRecordId, subjectId, emotionType, now, DailyRecordStatus.SAVED, DailyRecordStatus.DRAFT);
    }

    /**
     * 소유 <b>SAVED</b> record의 확정 감정만 교체하는 조건부 UPDATE. status는 바꾸지 않는다 —
     * DRAFT의 최초 감정 확정은 {@link #markSaved}가 계속 소유하고, 이 UPDATE는 저장 완료 후 수정 전용이다.
     * 조건 불일치(DRAFT·삭제됨·비소유)는 예외 대신 0으로 알려지고 호출부가 원인을 분류한다.
     *
     * <p>{@code markSaved}와 같은 이유로 bulk UPDATE가 JPA auditing을 우회하므로 {@code updated_at}을
     * 직접 채운다({@code modified_by}는 NULL 유지).
     */
    @Modifying
    @Transactional
    @Query("update DailyRecord r "
            + "set r.emotionType = :emotionType, r.updatedAt = :now "
            + "where r.dailyRecordId = :dailyRecordId and r.subjectId = :subjectId "
            + "and r.status = :saved and r.deletedAt is null")
    int updateSavedEmotion(@Param("dailyRecordId") Long dailyRecordId, @Param("subjectId") UUID subjectId,
                           @Param("emotionType") EmotionType emotionType, @Param("now") LocalDateTime now,
                           @Param("saved") DailyRecordStatus saved);

    default int updateSavedEmotion(Long dailyRecordId, UUID subjectId, EmotionType emotionType, LocalDateTime now) {
        return updateSavedEmotion(dailyRecordId, subjectId, emotionType, now, DailyRecordStatus.SAVED);
    }

    /**
     * 활성 record를 soft delete하고 하루 감정을 지운다(#568 — 삭제 기록은 분석용으로 남기되 감정은 남기지 않는다).
     * 이미 삭제된 행은 조건에서 빠져 0을 반환하므로 동시 삭제의 첫 시각이 보존된다. {@code markSaved}와 같은 이유로
     * {@code updated_at}을 직접 채운다.
     */
    @Modifying
    @Query("update DailyRecord r "
            + "set r.deletedAt = :now, r.emotionType = null, r.updatedAt = :now "
            + "where r.dailyRecordId = :dailyRecordId and r.deletedAt is null")
    int softDelete(@Param("dailyRecordId") Long dailyRecordId, @Param("now") LocalDateTime now);

    /**
     * 실패한 draft task가 남긴 빈 DRAFT를 soft delete하고 {@code modified_by}에 삭제 주체를 남긴다(#593 — 사용자 삭제와
     * 구분). 활성 Event가 없는 DRAFT만 조건에 걸리므로 SAVED·결과가 들어간 DRAFT·이미 삭제된 행은 0을 반환한다.
     * 감정은 DRAFT라 원래 NULL이고 지울 Event도 없다. 호출부가 transaction 밖이라 자체 transaction으로 실행하며,
     * {@code markSaved}와 같은 이유로 {@code updated_at}을 직접 채운다.
     */
    @Modifying
    @Transactional
    @Query("update DailyRecord r "
            + "set r.deletedAt = :now, r.updatedAt = :now, r.modifiedBy = :modifiedBy "
            + "where r.dailyRecordId = :dailyRecordId and r.status = :draft and r.deletedAt is null "
            + "and not exists (select 1 from TimelineEvent e "
            + "where e.dailyRecordId = r.dailyRecordId and e.deletedAt is null)")
    int softDeleteEmptyDraft(@Param("dailyRecordId") Long dailyRecordId, @Param("now") LocalDateTime now,
                             @Param("modifiedBy") String modifiedBy, @Param("draft") DailyRecordStatus draft);

    default int softDeleteEmptyDraft(Long dailyRecordId, LocalDateTime now, String modifiedBy) {
        return softDeleteEmptyDraft(dailyRecordId, now, modifiedBy, DailyRecordStatus.DRAFT);
    }
}
