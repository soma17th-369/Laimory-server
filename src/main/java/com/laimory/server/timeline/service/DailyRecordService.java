package com.laimory.server.timeline.service;

import com.laimory.server.timeline.EmotionType;
import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.repository.DailyRecordRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** daily_records leaf 서비스. 자신과 1:1인 DailyRecordRepository에만 접근한다. */
@Service
@RequiredArgsConstructor
public class DailyRecordService {

    private final DailyRecordRepository dailyRecordRepository;
    private final Clock clock;

    public Optional<DailyRecord> findBySubjectIdAndRecordDate(UUID subjectId, LocalDate recordDate) {
        return dailyRecordRepository.findBySubjectIdAndRecordDate(subjectId, recordDate);
    }

    public Optional<DailyRecord> findById(Long id) {
        return dailyRecordRepository.findById(id);
    }

    /** 사용자의 일일 기록 전체를 record_date, daily_record_id 내림차순으로 반환한다. */
    public List<DailyRecord> findBySubjectIdOrderByRecordDateDescDailyRecordIdDesc(UUID subjectId) {
        return dailyRecordRepository.findBySubjectIdOrderByRecordDateDescDailyRecordIdDesc(subjectId);
    }

    /** 일일 기록 ID와 subject가 모두 일치하는 소유 record만 반환한다. */
    public Optional<DailyRecord> findByDailyRecordIdAndSubjectId(Long dailyRecordId, UUID subjectId) {
        return dailyRecordRepository.findByDailyRecordIdAndSubjectId(dailyRecordId, subjectId);
    }

    /**
     * 소유 record만 골라 record_date 오름차순으로 한 번에 반환한다. 없는 id는 결과에서 빠진다.
     * 빈 목록을 넘기면 질의 없이 빈 결과다({@code IN ()}는 문법 오류다).
     */
    public List<DailyRecord> findAllBySubjectIdAndIdsOrderByRecordDate(UUID subjectId,
                                                                      List<Long> dailyRecordIds) {
        if (dailyRecordIds.isEmpty()) {
            return List.of();
        }
        return dailyRecordRepository.findBySubjectIdAndDailyRecordIdInOrderByRecordDateAsc(
                subjectId, dailyRecordIds);
    }

    /** 소유 record를 양끝 포함 날짜 범위로 골라 record_date 오름차순으로 반환한다. */
    public List<DailyRecord> findBySubjectIdAndRecordDateBetweenOrderByRecordDateAsc(
            UUID subjectId, LocalDate startInclusive, LocalDate endInclusive) {
        return dailyRecordRepository
                .findBySubjectIdAndRecordDateGreaterThanEqualAndRecordDateLessThanEqualOrderByRecordDateAsc(
                        subjectId, startInclusive, endInclusive);
    }

    public DailyRecord save(DailyRecord dailyRecord) {
        return dailyRecordRepository.save(dailyRecord);
    }

    /** 하루 기록 행을 삭제한다. 하위 timeline_events/timeline_items는 DB FK {@code ON DELETE CASCADE}가 지운다. */
    public void deleteById(Long dailyRecordId) {
        dailyRecordRepository.deleteById(dailyRecordId);
    }

    /**
     * 소유 DRAFT record를 요청 감정과 함께 SAVED로 전이하고 실제로 옮긴 행 수를 반환한다
     * (0 = 이미 SAVED·삭제됨·비소유). 호출부의 트랜잭션에 합류하는 조건부 UPDATE라 별도 lock 없이
     * 동시 저장 중 하나만 성공하고, 승자의 감정만 남는다.
     */
    public int markSaved(Long dailyRecordId, UUID subjectId, EmotionType emotionType) {
        return dailyRecordRepository.markSaved(dailyRecordId, subjectId, emotionType, LocalDateTime.now(clock));
    }

    /**
     * 소유 SAVED record의 확정 감정만 교체하고 실제로 바꾼 행 수를 반환한다(0 = DRAFT·삭제됨·비소유).
     * status는 건드리지 않는다 — 최초 감정 확정은 {@link #markSaved}가 소유한다.
     */
    public int updateSavedEmotion(Long dailyRecordId, UUID subjectId, EmotionType emotionType) {
        return dailyRecordRepository.updateSavedEmotion(
                dailyRecordId, subjectId, emotionType, LocalDateTime.now(clock));
    }

    /**
     * (subjectId, recordDate)로 DRAFT를 찾거나 없으면 생성한다. draft POST의 선생성 트랜잭션
     * ({@link TimelineDraftPreparationService})에 합류({@code REQUIRED})하므로 record 생성이 source 저장과
     * all-or-nothing으로 묶인다 — 후속 단계가 실패하면 신규 record도 롤백된다.
     *
     * <p>{@code REQUIRED}라 unique 위반을 catch해 재조회하는 옛 upsert는 못 쓴다 — 위반이 합류한 트랜잭션을
     * rollback-only로 오염시키기 때문이다. 같은 날짜의 동시 생성 경합은 별도 직렬화 없이 unique 위반이
     * 그대로 전파된다. 이 동시성 계약의 보완은 후속 작업 범위다.
     *
     * <p>같은 날짜 재요청(append)이면 기존 DRAFT의 record_at/record_timezone을 이번 요청 값으로 즉시 갱신한다
     * (AI 성공 시점이 아니라 draft 요청 시점 기준). 관리 엔티티라 dirty-checking으로 합류 트랜잭션 커밋 시
     * flush되며 repo.save는 없다. SAVED는 따로 거르지 않는다 — 유일 호출부가 직후 SAVED를 거절(throw)해
     * 롤백하므로, 갱신은 flush 전에 폐기된다.
     */
    @Transactional
    public DailyRecord findOrCreateDraft(UUID subjectId, LocalDate recordDate, LocalDateTime recordAt,
                                         String recordTimezone) {
        return dailyRecordRepository.findBySubjectIdAndRecordDate(subjectId, recordDate)
                .map(existing -> {
                    existing.updateRecordTime(recordAt, recordTimezone);
                    return existing;
                })
                .orElseGet(() -> dailyRecordRepository.save(
                        DailyRecord.createDraft(subjectId, recordDate, recordAt, recordTimezone)));
    }
}
