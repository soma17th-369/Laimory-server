package com.laimory.server.timeline.service;

import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.repository.DailyRecordRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** daily_records leaf 서비스. 자신과 1:1인 DailyRecordRepository에만 접근한다. */
@Service
@RequiredArgsConstructor
public class DailyRecordService {

    private final DailyRecordRepository dailyRecordRepository;

    public Optional<DailyRecord> findByUserIdAndRecordDate(Long userId, LocalDate recordDate) {
        return dailyRecordRepository.findByUserIdAndRecordDate(userId, recordDate);
    }

    public Optional<DailyRecord> findById(Long id) {
        return dailyRecordRepository.findById(id);
    }

    /** 사용자의 일일 기록 전체를 record_date, daily_record_id 내림차순으로 반환한다. */
    public List<DailyRecord> findByUserIdOrderByRecordDateDescDailyRecordIdDesc(Long userId) {
        return dailyRecordRepository.findByUserIdOrderByRecordDateDescDailyRecordIdDesc(userId);
    }

    /** 일일 기록 ID와 사용자 ID가 모두 일치하는 소유 record만 반환한다. */
    public Optional<DailyRecord> findByDailyRecordIdAndUserId(Long dailyRecordId, Long userId) {
        return dailyRecordRepository.findByDailyRecordIdAndUserId(dailyRecordId, userId);
    }

    public DailyRecord save(DailyRecord dailyRecord) {
        return dailyRecordRepository.save(dailyRecord);
    }

    /** 하루 기록 행을 삭제한다. 하위 timeline_events/timeline_items는 DB FK {@code ON DELETE CASCADE}가 지운다. */
    public void deleteById(Long dailyRecordId) {
        dailyRecordRepository.deleteById(dailyRecordId);
    }

    /**
     * (userId, recordDate)로 DRAFT를 찾거나 없으면 생성한다. draft POST의 선생성 트랜잭션
     * ({@link TimelineDraftPreparationService})에 합류({@code REQUIRED})하므로 record 생성이 source 저장과
     * all-or-nothing으로 묶인다 — 후속 단계가 실패하면 신규 record도 롤백된다.
     *
     * <p>{@code REQUIRED}라 unique 위반을 catch해 재조회하는 옛 upsert는 못 쓴다 — 위반이 합류한 트랜잭션을
     * rollback-only로 오염시키기 때문이다. 동시 생성 경합은 날짜 guard가 이 앞에서 직렬화하므로 정상 경로에서
     * 나지 않고, 발생 시 그대로 전파돼 POST가 실패한다(guard 해제 후 클라 재시도로 수렴).
     *
     * <p>같은 날짜 재요청(append)이면 기존 DRAFT의 record_at/record_timezone을 이번 요청 값으로 즉시 갱신한다
     * (AI 성공 시점이 아니라 draft 요청 시점 기준). 관리 엔티티라 dirty-checking으로 합류 트랜잭션 커밋 시
     * flush되며 repo.save는 없다. SAVED는 따로 거르지 않는다 — 유일 호출부가 직후 SAVED를 거절(throw)해
     * 롤백하므로, 갱신은 flush 전에 폐기된다.
     */
    @Transactional
    public DailyRecord findOrCreateDraft(Long userId, LocalDate recordDate, LocalDateTime recordAt,
                                         String recordTimezone) {
        return dailyRecordRepository.findByUserIdAndRecordDate(userId, recordDate)
                .map(existing -> {
                    existing.updateRecordTime(recordAt, recordTimezone);
                    return existing;
                })
                .orElseGet(() -> dailyRecordRepository.save(
                        DailyRecord.createDraft(userId, recordDate, recordAt, recordTimezone)));
    }
}
