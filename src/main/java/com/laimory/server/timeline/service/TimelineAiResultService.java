package com.laimory.server.timeline.service;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.timeline.TaskStatus;
import com.laimory.server.timeline.TaskTokens;
import com.laimory.server.timeline.dto.AiTimelineResultRequest;
import com.laimory.server.timeline.dto.AiTimelineResultResponse;
import com.laimory.server.timeline.entity.TimelineDraftTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * AI 결과 저장 오케스트레이터 — 토큰 검증과 형식 검증을 마친 뒤 저장 transaction을 호출하고, 커밋 후
 * PROCESSING TTL을 다시 확보한다. task 상태 전이(SUCCESS/FAILED)는 <b>여기서 하지 않는다</b> —
 * 그것은 기존 콜백 endpoint의 책임이며, 이 endpoint는 graph 저장만 담당한다.
 *
 * <p>재시도 계약: 같은 task의 결과가 이미 저장돼 있으면(영수증 duplicate key) graph를 건드리지 않고
 * 성공으로 응답한다. 응답만 유실된 재시도가 중복 graph를 만들지 않게 하는 지점이며, 판정 권위는
 * DB 영수증이다(토큰 파생 chain은 순서를 강제하지 못한다).
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
        if (!task.matchesResultToken(taskToken)) {
            log.warn("ai result token mismatch: taskId={}", taskId);
            throw new BusinessException(ExceptionType.TASK_TOKEN_MISMATCH);
        }
        if (task.status() != TaskStatus.PROCESSING) {
            log.warn("ai result on terminal task: taskId={} status={}", taskId, task.status());
            throw new BusinessException(ExceptionType.DRAFT_TASK_STATE_CONFLICT);
        }

        // DB를 보지 않는 형식 검증을 먼저 해 transaction을 열지 않고 400으로 끝낸다.
        TimelineAiResultTransactionService.requireValidShape(request);

        try {
            timelineAiResultTransactionService.store(taskId, task.dailyRecordId(), request);
        } catch (DataIntegrityViolationException e) {
            // 영수증 duplicate key = 이미 반영된 task의 재시도. graph는 그대로 두고 성공으로 응답한다.
            log.info("ai result already stored, treating retry as success: taskId={}", taskId);
        }

        // 저장(또는 이미 반영 확인) 이후에만 TTL을 연장한다 — 콜백까지 남은 구간에서 task가 만료되지 않게 한다.
        timelineTaskService.refreshProcessing(taskId, task);

        // 다음 단계(콜백) 토큰은 제시된 결과 토큰에서 결정적으로 파생한다 — 재시도해도 같은 값이다.
        return new AiTimelineResultResponse(TaskTokens.deriveCallbackToken(taskToken, taskId));
    }
}
