package com.laimory.server.timeline.service;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.common.id.UuidV7;
import com.laimory.server.timeline.DailyRecordStatus;
import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.entity.TimelineEvent;
import java.time.Duration;
import java.time.LocalDate;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 사용자 타임라인 삭제(Event·DailyRecord) 오케스트레이터. leaf 서비스를 합성한다(레포 직접 접근 금지).
 *
 * <p>공통 순서가 load-bearing이다:
 * 조회·소유권/상태 사전 검증(404 은닉·SAVED 409 — 아무 부수효과 전에 거절) → 날짜 guard를
 * {@code delete:{operationId}} holder로 선점(실패 = 같은 날짜 AI 작업/사진추가/삭제 진행 중 → 409 -1016) →
 * 별도 트랜잭션에서 재확인 후 PHOTO 삭제 job enqueue + DB hard delete
 * ({@link TimelineDeletionTransactionService} — Event/junction은 DB {@code ON DELETE CASCADE},
 * non-PHOTO orphan은 명시 삭제, valid PHOTO orphan은 job과 함께 보존).
 *
 * <p>S3 삭제는 request 경로에서 실행하지 않는다. 마지막 Event 참조가 사라지는 PHOTO Item 보존과 삭제
 * job, root/non-PHOTO hard delete가 같은 MySQL transaction으로 commit되면 API가 성공한다. 별도 worker가
 * S3를 재시도하고 성공 뒤 PHOTO Item과 job을 최종 hard delete한다.
 *
 * <p>guard는 <b>성공·500 모든 종료 경로에서 finally로 compare-and-release</b>한다 — 실패 시
 * 미해제면 클라 재시도가 TTL(1h) 동안 -1016으로 막혀 "재시도로 수렴" 설계가 깨진다.
 * 해제 자체는 best-effort다(예외는 삼키고 WARN — TTL이 안전망).
 *
 * <p>마지막 Event를 지워도 DailyRecord는 유지한다 — 하루 전체 제거는 DailyRecord 삭제만 담당한다.
 * 로그에 객체 key·URL·메모 내용은 남기지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TimelineDeletionService {

    private final TimelineEventService timelineEventService;
    private final DailyRecordService dailyRecordService;
    private final TimelineTaskService timelineTaskService;
    private final TimelineDeletionTransactionService timelineDeletionTransactionService;
    private final TimelinePhotoDeleteMetrics timelinePhotoDeleteMetrics;

    /** Event/non-PHOTO orphan을 hard delete하고 PHOTO orphan Item과 삭제 job을 남긴다. */
    public void deleteEvent(String applicationVersion, long userId, Long timelineEventId) {
        // applicationVersion: 버전별 처리 분기 지점(현재 단일 버전이라 분기 없음).
        TimelineEvent event = timelineEventService.findById(timelineEventId)
                .orElseThrow(() -> new BusinessException(ExceptionType.TIMELINE_EVENT_NOT_FOUND));
        DailyRecord record = requireOwnedDraftRecord(userId, event.getDailyRecordId(),
                ExceptionType.TIMELINE_EVENT_NOT_FOUND);
        deleteUnderDateGuard(userId, record.getRecordDate(), "event", timelineEventId,
                () -> timelineDeletionTransactionService.deleteEvent(userId, timelineEventId));
    }

    /** Record·Events/non-PHOTO orphan을 hard delete하고 PHOTO orphan Item과 삭제 job을 남긴다. */
    public void deleteDailyRecord(String applicationVersion, long userId, Long dailyRecordId) {
        // applicationVersion: 버전별 처리 분기 지점(현재 단일 버전이라 분기 없음).
        DailyRecord record = requireOwnedDraftRecord(userId, dailyRecordId, ExceptionType.DAILY_RECORD_NOT_FOUND);
        deleteUnderDateGuard(userId, record.getRecordDate(), "dailyRecord", dailyRecordId,
                () -> timelineDeletionTransactionService.deleteDailyRecord(userId, dailyRecordId));
    }

    /**
     * 날짜 guard 선점 → PHOTO job enqueue + hard delete transaction → finally 해제.
     * 같은 날짜 AI append와 PHOTO 추가가 transaction의 junction snapshot을 바꾸지 못하도록 guard 안에서
     * transaction bean을 호출한다.
     */
    private void deleteUnderDateGuard(long userId, LocalDate recordDate, String target, Long targetId,
                                      Supplier<TimelineDeletionTransactionService.DeletionResult> dbDelete) {
        long totalStartNanos = System.nanoTime();
        String operationId = UuidV7.randomUuidV7().toString();
        String guardHolder = TimelineTaskService.deleteGuardHolder(operationId);
        if (!timelineTaskService.claimDateGuard(userId, recordDate, guardHolder)) {
            throw new BusinessException(ExceptionType.RECORD_DATE_IN_PROGRESS);
        }
        try {
            TimelineDeletionTransactionService.DeletionResult result = dbDelete.get();
            timelinePhotoDeleteMetrics.recordEnqueueScheduled(result.scheduled());
            timelinePhotoDeleteMetrics.recordEnqueueSharedRetained(result.sharedRetained());
            timelinePhotoDeleteMetrics.recordEnqueueInvalidSkipped(result.invalidSkipped());
            log.info("timeline DB 삭제 commit 완료: target={} id={} totalElapsedMs={}",
                    target, targetId,
                    Duration.ofNanos(System.nanoTime() - totalStartNanos).toMillis());
        } finally {
            releaseDateGuardQuietly(userId, recordDate, guardHolder, operationId);
        }
    }

    /** record 없음·비소유는 {@code notFoundType}(404 은닉), SAVED는 409(-1003)로 사전 거절한다. */
    private DailyRecord requireOwnedDraftRecord(long userId, Long dailyRecordId, ExceptionType notFoundType) {
        DailyRecord record = dailyRecordService.findById(dailyRecordId)
                .filter(owned -> owned.getUserId() == userId)
                .orElseThrow(() -> new BusinessException(notFoundType));
        if (record.getStatus() == DailyRecordStatus.SAVED) {
            throw new BusinessException(ExceptionType.DAILY_RECORD_ALREADY_SAVED);
        }
        return record;
    }

    /** guard 해제는 best-effort — 실패해도 TTL(1h)이 자연 해제하는 안전망이 있어 원래 예외/결과를 막지 않는다. */
    private void releaseDateGuardQuietly(long userId, LocalDate recordDate, String holder, String operationId) {
        try {
            timelineTaskService.releaseDateGuard(userId, recordDate, holder);
        } catch (RuntimeException e) {
            log.warn("date guard release failed (TTL로 자연 해제 예정): operationId={} detail={}",
                    operationId, e.getMessage());
        }
    }
}
