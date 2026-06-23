package com.laimory.server.timeline.service;

import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.repository.DailyRecordRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
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

    public DailyRecord save(DailyRecord dailyRecord) {
        return dailyRecordRepository.save(dailyRecord);
    }

    /**
     * (userId, recordDate)로 DRAFT를 찾거나 없으면 생성한다. finalize(콜백) 트랜잭션에 합류({@code REQUIRED})하므로
     * record 생성이 event/item 저장·draft 삭제와 all-or-nothing으로 묶인다 — 후속 단계가 실패하면 record도 롤백돼
     * 고아 DRAFT가 남지 않는다(이전 {@code REQUIRES_NEW}는 record를 별도 커밋해 고아를 만들었다).
     *
     * <p>{@code REQUIRED}라 unique 위반을 catch해 재조회하는 옛 upsert는 더 못 쓴다 — 위반이 합류한 트랜잭션을
     * rollback-only로 오염시켜 같은 트랜잭션 안의 재조회가 무의미해지기 때문이다. 대신 동시 생성 경합은 그대로 전파시켜
     * finalize를 롤백하고, AI 콜백이 멱등 재시도로 마무리하게 한다(재시도 시 상대가 만든 기존 record를 재사용).
     * (이 메서드의 유일 caller는 finalize 경로다.)
     */
    @Transactional
    public DailyRecord findOrCreateDraft(Long userId, LocalDate recordDate, LocalDateTime recordAt,
                                         String recordTimezone) {
        return dailyRecordRepository.findByUserIdAndRecordDate(userId, recordDate)
                .orElseGet(() -> dailyRecordRepository.save(
                        DailyRecord.createDraft(userId, recordDate, recordAt, recordTimezone)));
    }
}
