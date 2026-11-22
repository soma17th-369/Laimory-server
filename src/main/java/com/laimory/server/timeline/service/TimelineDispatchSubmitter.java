package com.laimory.server.timeline.service;

import com.laimory.server.timeline.dto.AiTimelineDispatchRequest;
import com.laimory.server.timeline.entity.TimelineDraftTask;
import java.util.UUID;

/**
 * draft 생성이 AI 접수를 맡기는 접수 경로(#515). 선택은 {@code app.ai.dispatch.timeline.route}다.
 *
 * <ul>
 *   <li>{@code inline}(기본, {@link InlineTimelineDispatchSubmitter}): 요청 스레드에서 바로 접수한다(#515 이전 동작).</li>
 *   <li>{@code queue}({@link QueuedTimelineDispatchSubmitter}): 접수 대기열(SQS)에 적재하고 worker가 세션 생성률 한도
 *       안에서 접수한다.</li>
 * </ul>
 *
 * <p>누구에게 보내는지({@code app.ai.mode} — noop·fake·http·agentcore)는 {@link TimelineAiDispatcher}가 정하며 이 경로
 * 선택과 독립이다.
 */
public interface TimelineDispatchSubmitter {

    /**
     * 선생성 commit 뒤 호출된다. 유실 타이머·PROCESSING 저장·접수(또는 적재)를 맡는다. token은 구현이 접수 직전에 만든다.
     *
     * <ul>
     *   <li>유실 타이머·task 저장이 실패하면 보상한 뒤 원래 예외를 던진다.</li>
     *   <li>미접수(미적재) 확정이면 보상·FAILED 종결 뒤 {@code BusinessException(AI_DISPATCH_FAILED)}를 던진다(502).</li>
     *   <li>접수·적재가 확인됐거나 결과를 모르면 정상 반환한다(202, PROCESSING 유지).</li>
     * </ul>
     *
     * @param timelineWindow Redis task에 보존할 local 원본 window
     * @param offsetWindow   AI 접수 body에 실을 offset window(POST 시점의 record timezone으로 변환한 스냅샷)
     */
    void submit(String taskId, UUID subjectId, long dailyRecordId, TimelineDraftTask.TimelineWindow timelineWindow,
                AiTimelineDispatchRequest.Window offsetWindow);
}
