package com.laimory.server.timeline.service;

import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.repository.DailyRecordRepository;
import java.time.LocalDate;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
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
     * (userId, recordDate)로 DRAFT를 찾거나 없으면 생성한다. 동시 INSERT 경합 시 unique 위반을
     * catch해 재조회로 흡수한다(lock-free 멱등 upsert). 별도 트랜잭션(REQUIRES_NEW)으로 돌려
     * 위반이 호출자 트랜잭션을 rollback-only로 오염시키지 않게 하고, saveAndFlush로 위반을 즉시 발생시킨다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public DailyRecord findOrCreateDraft(Long userId, LocalDate recordDate) {
        return dailyRecordRepository.findByUserIdAndRecordDate(userId, recordDate)
                .orElseGet(() -> {
                    try {
                        return dailyRecordRepository.saveAndFlush(DailyRecord.createDraft(userId, recordDate));
                    } catch (DataIntegrityViolationException e) {
                        return dailyRecordRepository.findByUserIdAndRecordDate(userId, recordDate)
                                .orElseThrow(() -> e);
                    }
                });
    }
}
