package com.laimory.server.timeline.service;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.timeline.repository.UserMemoryUpdatePendingStore;
import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.entity.TimelineEvent;
import java.time.Duration;
import java.time.LocalDate;
import java.util.function.Supplier;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 사용자 타임라인 삭제(Event·DailyRecord) 오케스트레이터. leaf 서비스를 합성한다(레포 직접 접근 금지).
 *
 * <p>공통 순서가 load-bearing이다:
 * 조회·소유권 사전 검증(404 은닉 — 아무 부수효과 전에 거절) →
 * 별도 트랜잭션에서 재확인 후 DB 변경({@link TimelineDeletionTransactionService}).
 *
 * <p>Event·DailyRecord 삭제는 soft delete다(#568) — 행·junction·Item을 남기고 {@code deleted_at}만 찍으며,
 * 감정·장소명·주소는 지운다. 삭제된 행은 앱 조회에서 빠지고 분석용으로 회원 탈퇴 시까지 남는다.
 * Event-Item 연결 해제만 junction을 hard delete하고, 마지막 참조였던 PHOTO는 S3 삭제 job으로 넘긴다
 * (S3 삭제는 request 경로에서 실행하지 않는다).
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
    private final UserMemoryUpdatePendingStore userMemoryUpdatePendingStore;
    private final TimelineDeletionTransactionService timelineDeletionTransactionService;

    /** Event를 soft delete한다. */
    public void deleteEvent(String applicationVersion, UUID subjectId, Long timelineEventId) {
        // applicationVersion: 버전별 처리 분기 지점(현재 단일 버전이라 분기 없음).
        TimelineEvent event = timelineEventService.findById(timelineEventId)
                .orElseThrow(() -> new BusinessException(ExceptionType.TIMELINE_EVENT_NOT_FOUND));
        requireOwnedRecord(subjectId, event.getDailyRecordId(),
                ExceptionType.TIMELINE_EVENT_NOT_FOUND);
        timelineDeletionTransactionService.deleteEvent(subjectId, timelineEventId);
        log.info("timeline soft delete commit 완료: target=event id={}", timelineEventId);
    }

    /**
     * Event에서 PHOTO Item 연결만 해제한다. 마지막 참조였으면 PHOTO orphan Item과 삭제 job을 남긴다.
     * 사전 guard는 event·record까지만이고 junction 존재·삭제 판정은 트랜잭션이 소유한다
     * ({@link TimelineDeletionTransactionService#detachEventItem} — 직접 DELETE 영향 행 수 기반).
     */
    public void detachEventItem(String applicationVersion, UUID subjectId,
                                Long timelineEventId, Long timelineItemId) {
        // applicationVersion: 버전별 처리 분기 지점(현재 단일 버전이라 분기 없음).
        TimelineEvent event = timelineEventService.findById(timelineEventId)
                .orElseThrow(() -> new BusinessException(ExceptionType.TIMELINE_EVENT_NOT_FOUND));
        requireOwnedRecord(subjectId, event.getDailyRecordId(),
                ExceptionType.TIMELINE_EVENT_NOT_FOUND);
        executeDelete("eventItem", timelineItemId,
                () -> timelineDeletionTransactionService.detachEventItem(subjectId, timelineEventId, timelineItemId));
    }

    /** Record와 그 Event들을 soft delete한다. */
    public void deleteDailyRecord(String applicationVersion, UUID subjectId, Long dailyRecordId) {
        // applicationVersion: 버전별 처리 분기 지점(현재 단일 버전이라 분기 없음).
        requireOwnedRecord(subjectId, dailyRecordId, ExceptionType.DAILY_RECORD_NOT_FOUND);
        timelineDeletionTransactionService.deleteDailyRecord(subjectId, dailyRecordId);
        log.info("timeline soft delete commit 완료: target=dailyRecord id={}", dailyRecordId);
        removePendingForDeletedRecord(subjectId, dailyRecordId);
    }

    /** 날짜로 찾은 Record와 그 Event들을 soft delete한다. */
    public void deleteDailyRecordByDate(String applicationVersion, UUID subjectId, LocalDate recordDate) {
        // applicationVersion: 버전별 처리 분기 지점(현재 단일 버전이라 분기 없음).
        DailyRecord record = dailyRecordService.findBySubjectIdAndRecordDate(subjectId, recordDate)
                .orElseThrow(() -> new BusinessException(ExceptionType.DAILY_RECORD_NOT_FOUND));
        Long dailyRecordId = record.getDailyRecordId();
        timelineDeletionTransactionService.deleteDailyRecord(subjectId, dailyRecordId);
        log.info("timeline soft delete commit 완료: target=dailyRecord id={}", dailyRecordId);
        removePendingForDeletedRecord(subjectId, dailyRecordId);
    }

    /**
     * 삭제된 하루를 User Memory 미반영 큐에서도 뺀다 — 갱신할 재료가 사라졌기 때문이다.
     *
     * <p><b>반드시 commit 이후에 부른다.</b> Redis는 DB transaction에 참여하지 않으므로 transaction
     * 안에서 지우면, commit이 실패했을 때 record는 되살아나는데 큐 member만 사라져 그 하루가 영영
     * 반영되지 않는다. 삭제가 성공적으로 반환된 뒤에만 실행되도록 호출 순서로 보장한다.
     *
     * <p>이걸 하지 않으면 그 member는 record id를 어디서도 얻을 수 없어 id 기반 정리로는 안 잡히고,
     * 일일 배치가 재료 없음으로 걷어낼 때까지 큐에 남는다.
     */
    private void removePendingForDeletedRecord(UUID subjectId, Long dailyRecordId) {
        userMemoryUpdatePendingStore.removeAll(subjectId, List.of(dailyRecordId));
    }

    /** 연결 해제의 PHOTO job enqueue + hard delete transaction 결과를 안전한 구조화 로그에 반영한다. */
    private void executeDelete(String target, Long targetId,
                               Supplier<TimelineDeletionTransactionService.DeletionResult> dbDelete) {
        long totalStartNanos = System.nanoTime();
        TimelineDeletionTransactionService.DeletionResult result = dbDelete.get();
        log.info("timeline DB 삭제 commit 완료: target={} id={} photoDeleteScheduled={} "
                        + "sharedPhotoRetained={} invalidPhotoSkipped={} totalElapsedMs={}",
                target, targetId, result.scheduled(), result.sharedRetained(), result.invalidSkipped(),
                Duration.ofNanos(System.nanoTime() - totalStartNanos).toMillis());
    }

    /** record 없음·비소유는 {@code notFoundType}(404 은닉)으로 사전 거절한다. */
    private void requireOwnedRecord(UUID subjectId, Long dailyRecordId, ExceptionType notFoundType) {
        dailyRecordService.findById(dailyRecordId)
                .filter(owned -> owned.getSubjectId().equals(subjectId))
                .orElseThrow(() -> new BusinessException(notFoundType));
    }
}
