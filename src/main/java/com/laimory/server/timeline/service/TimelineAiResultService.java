package com.laimory.server.timeline.service;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.timeline.ProcessStage;
import com.laimory.server.timeline.TaskStatus;
import com.laimory.server.timeline.TaskTokens;
import com.laimory.server.timeline.dto.AiTimelineResultRequest;
import com.laimory.server.timeline.dto.AiTimelineResultResponse;
import com.laimory.server.timeline.entity.TimelineDraftTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * AI 결과를 저장한다. RESULT token을 CALLBACK token으로 먼저 교체해 동시 writer를 하나로 제한하며,
 * MySQL 저장 실패 시 가능한 경우 이전 RESULT token으로 되돌린다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TimelineAiResultService {

    private final TimelineTaskService timelineTaskService;
    private final TimelineAiResultTransactionService timelineAiResultTransactionService;

    public AiTimelineResultResponse storeResult(String applicationVersion, String taskId, String taskToken,
                                                AiTimelineResultRequest request) {
        // applicationVersion: 버전별 처리 분기 지점(현재 단일 버전이라 분기 없음).
        TimelineDraftTask task = timelineTaskService.find(taskId)
                .orElseThrow(() -> new BusinessException(ExceptionType.DRAFT_TASK_NOT_FOUND));
        if (!task.matchesToken(taskToken)) {
            log.warn("ai result token mismatch: taskId={}", taskId);
            throw new BusinessException(ExceptionType.TASK_TOKEN_MISMATCH);
        }
        if (task.status() != TaskStatus.PROCESSING) {
            log.warn("ai result on terminal task: taskId={} status={}", taskId, task.status());
            throw new BusinessException(ExceptionType.DRAFT_TASK_STATE_CONFLICT);
        }
        if (task.stage() != ProcessStage.RESULT_PENDING) {
            log.warn("ai result on invalid stage: taskId={} stage={}", taskId, task.stage());
            throw new BusinessException(ExceptionType.DRAFT_TASK_STATE_CONFLICT);
        }

        // DB를 보지 않는 형식 검증을 먼저 해 transaction을 열지 않고 400으로 끝낸다.
        TimelineAiResultTransactionService.requireValidShape(request);

        String callbackToken = TaskTokens.generate();
        String callbackTokenHash = TaskTokens.hash(callbackToken);
        TimelineDraftTask claimed = task.withTokenAndStage(callbackTokenHash, ProcessStage.CALLBACK_PENDING);
        if (!timelineTaskService.replaceProcessing(taskId, task, claimed)) {
            log.warn("ai result token rotation lost race: taskId={}", taskId);
            throw new BusinessException(ExceptionType.DRAFT_TASK_STATE_CONFLICT);
        }

        try {
            timelineAiResultTransactionService.store(taskId, task.dailyRecordId(), request);
        } catch (RuntimeException storageFailure) {
            try {
                if (!timelineTaskService.replaceProcessing(taskId, claimed, task)) {
                    log.warn("ai result token rollback skipped after storage failure: taskId={}", taskId);
                }
            } catch (RuntimeException tokenFailure) {
                storageFailure.addSuppressed(tokenFailure);
                log.warn("ai result token rollback failed: taskId={}", taskId, tokenFailure);
            }
            throw storageFailure;
        }

        return new AiTimelineResultResponse(callbackToken);
    }
}
