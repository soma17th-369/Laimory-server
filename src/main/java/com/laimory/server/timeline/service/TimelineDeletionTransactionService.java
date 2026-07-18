package com.laimory.server.timeline.service;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.timeline.DailyRecordStatus;
import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.entity.TimelineEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 타임라인 삭제의 DB 트랜잭션 경계 전담 빈. S3 배치 삭제(트랜잭션 밖) 성공 후
 * {@link TimelineDeletionService}가 Spring 프록시를 통해 호출한다 — 오케스트레이터 안에
 * {@code @Transactional} 메서드를 두면 self-invocation으로 트랜잭션이 조용히 무효화되므로 분리한다.
 *
 * <p>S3 삭제 동안 상태가 변했을 수 있어(사전 검증과 시차) 짧은 트랜잭션 안에서 소유권·DRAFT를
 * <b>재확인</b>한 뒤 부모 행만 {@code deleteById}로 지운다 — 하위 행(events/items)은 DB FK
 * {@code ON DELETE CASCADE}가 처리한다(JPA cascade 없음). 재확인 실패는 사전 검증과 같은
 * 404(은닉)/409(SAVED)로 거절한다.
 */
@Service
@RequiredArgsConstructor
public class TimelineDeletionTransactionService {

    private final TimelineEventService timelineEventService;
    private final DailyRecordService dailyRecordService;

    /** 소유권·DRAFT 재확인 후 이벤트 행을 삭제한다(하위 items는 DB cascade). */
    @Transactional
    public void deleteEvent(long userId, Long timelineEventId) {
        TimelineEvent event = timelineEventService.findById(timelineEventId)
                .orElseThrow(() -> new BusinessException(ExceptionType.TIMELINE_EVENT_NOT_FOUND));
        requireOwnedDraftRecord(userId, event.getDailyRecordId(), ExceptionType.TIMELINE_EVENT_NOT_FOUND);
        timelineEventService.deleteById(timelineEventId);
    }

    /** 소유권·DRAFT 재확인 후 하루 기록 행을 삭제한다(하위 events/items는 DB cascade). */
    @Transactional
    public void deleteDailyRecord(long userId, Long dailyRecordId) {
        requireOwnedDraftRecord(userId, dailyRecordId, ExceptionType.DAILY_RECORD_NOT_FOUND);
        dailyRecordService.deleteById(dailyRecordId);
    }

    /** record 없음·비소유는 {@code notFoundType}(404 은닉), SAVED는 409(ERROR_1003)로 거절한다. */
    private void requireOwnedDraftRecord(long userId, Long dailyRecordId, ExceptionType notFoundType) {
        DailyRecord record = dailyRecordService.findById(dailyRecordId)
                .filter(owned -> owned.getUserId() == userId)
                .orElseThrow(() -> new BusinessException(notFoundType));
        if (record.getStatus() == DailyRecordStatus.SAVED) {
            throw new BusinessException(ExceptionType.DAILY_RECORD_ALREADY_SAVED);
        }
    }
}
