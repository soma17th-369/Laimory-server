package com.laimory.server.timeline.service;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.timeline.DailyRecordStatus;
import com.laimory.server.timeline.EmotionType;
import com.laimory.server.timeline.entity.DailyRecord;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 감정 수정의 writer 전담 빈({@link TimelineSaveTransactionService}와 같은 형태). 서비스 transaction은
 * 없다(#499) — 조건부 UPDATE는 리포지토리 tx 한 문장으로 실행되고, 이 빈은 0행의 원인 분류를 소유한다.
 *
 * <p>첫 DB 작업이 SAVED 조건부 UPDATE다 — 0행 후의 분류 SELECT는 시작 시점 snapshot 없이 최신
 * 커밋 상태를 읽는다. 영향 행 수가 판정 기준이고, 0행의 원인은 DRAFT(409 {@code -1020})·
 * 없음/비소유(404)·동일 감정 SAVED(멱등 성공)로 분류한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DailyRecordEmotionUpdateTransactionService {

    private final DailyRecordService dailyRecordService;

    /**
     * 소유 SAVED record의 확정 감정을 교체한다. status는 바꾸지 않는다.
     *
     * @throws BusinessException 아직 DRAFT면 409 {@code -1020},
     *                           record가 사라졌거나 비소유면 404 {@code -404}
     * @throws IllegalStateException 0행인데 재조회가 다른 감정의 SAVED를 보여 주는 설명되지 않는
     *                               불일치(500) — 조건부 UPDATE 계약 위반 신호
     */
    public void updateEmotion(UUID subjectId, Long dailyRecordId, EmotionType emotionType) {
        if (dailyRecordService.updateSavedEmotion(dailyRecordId, subjectId, emotionType) == 1) {
            log.info("하루 감정 수정 commit: dailyRecordId={}", dailyRecordId);
            return;
        }
        classifyUpdateFailure(subjectId, dailyRecordId, emotionType);
    }

    /**
     * 조건부 UPDATE가 0행일 때의 원인을 분류한다. 행이 없거나 비소유면 존재를 노출하지 않는 기존 404
     * 계약으로, DRAFT면 수정할 확정 감정이 없다는 {@code -1020}으로 수렴한다. 같은 감정의 SAVED는
     * 결과가 이미 요청과 같으므로 멱등 성공이다.
     */
    private void classifyUpdateFailure(UUID subjectId, Long dailyRecordId, EmotionType emotionType) {
        DailyRecord record = dailyRecordService.findByDailyRecordIdAndSubjectId(dailyRecordId, subjectId)
                .orElseThrow(() -> new BusinessException(ExceptionType.DAILY_RECORD_NOT_FOUND));
        if (record.getStatus() == DailyRecordStatus.DRAFT) {
            throw new BusinessException(ExceptionType.DAILY_RECORD_NOT_SAVED);
        }
        if (record.getEmotionType() == emotionType) {
            log.info("하루 감정 수정 멱등 성공(이미 같은 값): dailyRecordId={}", dailyRecordId);
            return;
        }
        throw new IllegalStateException(
                "SAVED emotion update matched no row but record is SAVED with a different emotion: dailyRecordId="
                        + dailyRecordId);
    }
}
