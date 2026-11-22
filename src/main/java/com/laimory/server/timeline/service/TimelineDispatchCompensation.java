package com.laimory.server.timeline.service;

import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.credit.CreditCostType;
import com.laimory.server.credit.CreditRefundReason;
import com.laimory.server.credit.service.CreditService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 생성을 시작하지 못한 draft task의 보상(#515에서 {@link TimelineDraftTaskService}에서 분리). 새 환불 사유·오류 코드를
 * 만들지 않고 기존 {@code TASK_SAVE_FAILED}·{@code DISPATCH_REJECTED}·{@code -1009}를 그대로 쓴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TimelineDispatchCompensation {

    private final CreditService creditService;
    private final TimelineTaskLossTimerService timelineTaskLossTimerService;
    private final DailyRecordService dailyRecordService;
    private final TimelineTaskService timelineTaskService;
    private final TimelineDraftSourceItemService timelineDraftSourceItemService;

    /**
     * 유실 타이머·PROCESSING 저장이 실패했다 — 이번 task의 source rows를 지우고 환불(TASK_SAVE_FAILED)·빈 DRAFT
     * soft delete를 한다. 호출부는 원래 예외를 다시 던진다.
     */
    public void abandonUnsavedTask(String taskId, long dailyRecordId) {
        timelineDraftSourceItemService.deleteByTaskId(taskId);
        refundAndCancelTimerQuietly(taskId, CreditRefundReason.TASK_SAVE_FAILED);
        softDeleteFailedDraftQuietly(taskId, dailyRecordId);
    }

    /**
     * 미접수 확정 — AI가 접수·write하지 않았음이 확실하므로 환불(DISPATCH_REJECTED)·타이머 해제·빈 DRAFT 정리 뒤
     * FAILED({@code -1009}, 24h)로 종결을 시도한다. {@code tokenHash}는 task에 저장된 현재 hash다.
     */
    public void closeNotAccepted(String taskId, UUID subjectId, long dailyRecordId, String tokenHash) {
        refundAndCancelTimerQuietly(taskId, CreditRefundReason.DISPATCH_REJECTED);
        softDeleteFailedDraftQuietly(taskId, dailyRecordId);
        try {
            timelineTaskService.markFailed(taskId, subjectId, dailyRecordId, ExceptionType.AI_DISPATCH_FAILED, tokenHash);
        } catch (RuntimeException saveFailure) {
            // FAILED 저장까지 실패하면 task 상태는 불명 — read-back·재저장·retry 없이 로그만 남긴다.
            // PROCESSING으로 남았다면 최초 PROCESSING TTL이 회수한다.
            log.warn("timeline ai dispatch rejected, markFailed also failed (task state unknown): taskId={}",
                    taskId, saveFailure);
        }
    }

    /**
     * 생성을 시작하지 못한 task의 차감을 돌려주고 타이머를 지운다. 원래 실패를 덮지 않도록 실패는 로그만 남긴다 —
     * 환불이 실패하면 타이머를 지우지 않으므로, 타이머가 남았다면 만료(유실 처리)가 다시 환불한다.
     */
    private void refundAndCancelTimerQuietly(String taskId, CreditRefundReason reason) {
        try {
            creditService.refund(CreditCostType.TIMELINE_CREATION, taskId, reason);
            timelineTaskLossTimerService.cancel(taskId);
        } catch (RuntimeException e) {
            log.warn("timeline credit refund or timer cancel failed (타이머가 남았으면 만료가 환불): taskId={}",
                    taskId, e);
        }
    }

    /** 생성을 시작하지 못한 task의 빈 DRAFT를 정리한다. 원래 실패를 덮지 않도록 실패는 로그만 남긴다(빈 DRAFT 잔존). */
    private void softDeleteFailedDraftQuietly(String taskId, long dailyRecordId) {
        try {
            dailyRecordService.softDeleteFailedDraft(dailyRecordId);
        } catch (RuntimeException e) {
            log.warn("failed draft soft delete failed (빈 DRAFT 잔존): taskId={} dailyRecordId={}",
                    taskId, dailyRecordId, e);
        }
    }
}
