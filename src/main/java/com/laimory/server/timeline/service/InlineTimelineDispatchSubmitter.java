package com.laimory.server.timeline.service;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.timeline.TaskTokens;
import com.laimory.server.timeline.dto.AiTimelineDispatchRequest;
import com.laimory.server.timeline.entity.TimelineDraftTask;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 요청 스레드에서 바로 AI에 접수하는 경로(#515 이전 동작 그대로).
 *
 * <p>순서: 유실 타이머(처리 기한) → PROCESSING({@code INPUT_PENDING} + token hash) 저장 → 동기 접수(ack까지).
 * 미접수 확정(4xx 거절, throttle 포함)만 502로 실패시키고, 접수 확인과 UNKNOWN은 둘 다 PROCESSING이 살아 있으므로
 * 정상 반환한다. 자동 재전송은 하지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InlineTimelineDispatchSubmitter implements TimelineDispatchSubmitter {

    private final TimelineTaskService timelineTaskService;
    private final TimelineTaskLossTimerService timelineTaskLossTimerService;
    private final TimelineAiDispatcher timelineAiDispatcher;
    private final TimelineDispatchCompensation compensation;
    private final Clock clock;

    @Override
    public void submit(String taskId, UUID subjectId, long dailyRecordId, TimelineDraftTask.TimelineWindow timelineWindow,
                       AiTimelineDispatchRequest.Window offsetWindow) {
        // 입력 조회·결과 저장·콜백이 함께 쓰는 taskToken. 원문은 AI에 전달하고 Redis에는 SHA-256 hash만 저장한다.
        String taskToken = TaskTokens.generate();
        String tokenHash = TaskTokens.hash(taskToken);

        // 유실 타이머 + Redis PROCESSING 기록(dailyRecordId 포함 — 폴링·콜백 전이의 기준). 실패하면 이번 task의
        // source rows를 보상 삭제하고 환불·빈 DRAFT soft delete 뒤 전파한다. 저장 직전에 캡처하는
        // processingStartedAt이 폴링 elapsedSeconds의 기준이다.
        Instant processingStartedAt = clock.instant();
        try {
            timelineTaskLossTimerService.start(taskId);
            timelineTaskService.createProcessing(taskId, subjectId, dailyRecordId,
                    timelineWindow, tokenHash, processingStartedAt);
        } catch (RuntimeException e) {
            compensation.abandonUnsavedTask(taskId, dailyRecordId);
            throw e;
        }

        // AI dispatch — dailyRecordId·offset window는 유지하고 토큰 필드는 taskToken으로 통일한다. source item은 AI가
        // 이 토큰으로 서버간 입력 조회 API를 호출해 받아간다. 미접수 확정만 502(-1009)로 던지고 응답·예외 인자에
        // taskId를 싣지 않는다(진단은 아래 로그가 담당).
        try {
            timelineAiDispatcher.dispatch(new AiTimelineDispatchRequest(
                    taskId, taskToken, dailyRecordId, offsetWindow));
        } catch (TimelineAiDispatchRejectedException e) {
            // 미접수 확정(4xx 거절) — AI가 접수·write하지 않았음이 확실하므로 FAILED(24h) 종결을 시도한다.
            // 상세는 로그로만 남겨 응답·폴링 body.error로 유출하지 않는다.
            log.warn("timeline ai dispatch rejected (미접수 확정): taskId={} detail={}", taskId, e.getMessage());
            compensation.closeNotAccepted(taskId, subjectId, dailyRecordId, tokenHash);
            throw new BusinessException(ExceptionType.AI_DISPATCH_FAILED);
        } catch (RuntimeException e) {
            // UNKNOWN(read timeout·connect 실패·5xx·계약 불일치) — AI가 이미 접수해 final write를 진행 중일 수
            // 있다. FAILED로 덮거나 재저장으로 TTL을 연장하지 않고 기존 PROCESSING을 그대로 둔다.
            // AI callback 또는 유실 타이머 만료(처리 기한 3분)가 종결하며, draft도 보존한다.
            // 차감도 그대로 둔다 — 결과 저장이 확정하거나, FAILED callback·유실 타이머 만료가 환불한다.
            // 서버 상태가 정상 접수와 같으므로 응답도 202/taskId다 — 실패를 알린 뒤 결과가 생기는 불일치를 막고
            // 사용자는 폴링으로 결과를 본다(#600). 운영자가 원인을 보도록 ERROR로 남긴다(ERROR 로그 경보 대상).
            log.error("timeline ai dispatch outcome unknown, returning taskId and keeping PROCESSING: "
                    + "taskId={} detail={}", taskId, e.getMessage());
        }
    }
}
