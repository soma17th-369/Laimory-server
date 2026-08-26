package com.laimory.server.timeline.service;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.timeline.DailyRecordStatus;
import com.laimory.server.timeline.EmotionType;
import com.laimory.server.timeline.entity.DailyRecord;
import java.time.LocalDate;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 저장 완료 하루 기록의 감정 수정 오케스트레이터. leaf 서비스를 합성한다(레포 직접 접근 금지).
 *
 * <p>{@link TimelineSaveService}와 같은 경계다: 비트랜잭션 사전 검증(404 은닉·DRAFT 409) → 별도
 * 트랜잭션에서 SAVED 조건부 UPDATE 커밋({@link DailyRecordEmotionUpdateTransactionService}).
 * 사전 조회를 트랜잭션 밖에 두는 이유는 MySQL {@code REPEATABLE READ}에서 조회와 실패 재조회를 한
 * 트랜잭션에 묶으면 첫 조회 snapshot이 동시 삭제 전 행을 다시 보여 줄 수 있기 때문이다.
 *
 * <p>User Memory 갱신은 새로 enqueue하지 않는다 — SAVED Event 편집과 같은 현재 정책이다.
 */
@Service
@RequiredArgsConstructor
public class DailyRecordEmotionUpdateService {

    private final DailyRecordService dailyRecordService;
    private final DailyRecordEmotionUpdateTransactionService dailyRecordEmotionUpdateTransactionService;

    /**
     * 인증 사용자의 해당 날짜 SAVED 하루 기록의 확정 감정을 요청 값으로 교체한다. 같은 값 재요청도
     * 멱등 성공이다. 최초 감정 확정(DRAFT→SAVED)은 save API가 계속 담당한다.
     *
     * @throws BusinessException 없음·비소유 404 {@code -404}, 아직 DRAFT 409 {@code -1020}
     */
    public void updateEmotion(String applicationVersion, UUID subjectId, LocalDate recordDate,
                              EmotionType emotionType) {
        // applicationVersion: 버전별 처리 분기 지점(현재 단일 버전이라 분기 없음).
        DailyRecord record = dailyRecordService.findBySubjectIdAndRecordDate(subjectId, recordDate)
                .orElseThrow(() -> new BusinessException(ExceptionType.DAILY_RECORD_NOT_FOUND));
        if (record.getStatus() == DailyRecordStatus.DRAFT) {
            throw new BusinessException(ExceptionType.DAILY_RECORD_NOT_SAVED);
        }

        dailyRecordEmotionUpdateTransactionService.updateEmotion(
                subjectId, record.getDailyRecordId(), emotionType);
    }
}
