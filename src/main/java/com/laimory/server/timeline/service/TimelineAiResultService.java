package com.laimory.server.timeline.service;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.timeline.TaskStage;
import com.laimory.server.timeline.TaskStatus;
import com.laimory.server.timeline.dto.AiTimelineResultRequest;
import com.laimory.server.timeline.entity.TimelineDraftTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * AI 결과 저장 오케스트레이터. Redis {@code RESULT_WRITING} 선점으로 동시 writer를 하나로 제한한 뒤
 * MySQL graph transaction을 실행하고, 성공하면 {@code CALLBACK_PENDING}으로 전이한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TimelineAiResultService {

    private final TimelineTaskService timelineTaskService;
    private final TimelineAiResultTransactionService timelineAiResultTransactionService;

    public void storeResult(String applicationVersion, String taskId, String taskToken,
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

        // DB를 보지 않는 형식 검증을 먼저 해 transaction을 열지 않고 400으로 끝낸다.
        TimelineAiResultTransactionService.requireValidShape(request);

        if (task.stage() == TaskStage.CALLBACK_PENDING) {
            log.info("ai result replay accepted after stored result: taskId={}", taskId);
            return;
        }
        if (task.stage() != TaskStage.RESULT_PENDING) {
            log.warn("ai result on invalid stage: taskId={} stage={}", taskId, task.stage());
            throw new BusinessException(ExceptionType.DRAFT_TASK_STATE_CONFLICT);
        }

        TimelineDraftTask writing = task.withStage(TaskStage.RESULT_WRITING);
        if (!timelineTaskService.transitionStage(taskId, task, TaskStage.RESULT_WRITING)) {
            TimelineDraftTask latest = timelineTaskService.find(taskId)
                    .orElseThrow(() -> new BusinessException(ExceptionType.DRAFT_TASK_NOT_FOUND));
            if (latest.status() == TaskStatus.PROCESSING && latest.stage() == TaskStage.CALLBACK_PENDING) {
                log.info("ai result replay accepted after concurrent store: taskId={}", taskId);
                return;
            }
            log.warn("ai result stage claim failed: taskId={} status={} stage={}",
                    taskId, latest.status(), latest.stage());
            throw new BusinessException(ExceptionType.DRAFT_TASK_STATE_CONFLICT);
        }

        try {
            timelineAiResultTransactionService.store(taskId, task.dailyRecordId(), request);
        } catch (RuntimeException storageFailure) {
            try {
                if (!timelineTaskService.transitionStage(taskId, writing, TaskStage.RESULT_PENDING)) {
                    log.warn("ai result stage rollback skipped after storage failure: taskId={}", taskId);
                }
            } catch (RuntimeException stageFailure) {
                storageFailure.addSuppressed(stageFailure);
                log.warn("ai result stage rollback failed: taskId={}", taskId, stageFailure);
            }
            throw storageFailure;
        }

        if (!timelineTaskService.transitionStage(taskId, writing, TaskStage.CALLBACK_PENDING)) {
            throw new IllegalStateException("저장된 AI 결과의 callback stage 전이에 실패했습니다: " + taskId);
        }
    }
}
