package com.laimory.server.timeline.agentcore;

import com.laimory.server.timeline.dto.AgentCoreDispatchRequest;
import com.laimory.server.timeline.dto.AiTimelineDispatchRequest;
import com.laimory.server.timeline.service.TimelineAiDispatcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * AgentCore 타임라인 디스패처({@code app.ai.mode=agentcore} — #338). 기존 접수 body를 그대로
 * {@code {"requestType":"TIMELINE","payload":{...}}} wrapper에 담아 Bedrock AgentCore Runtime의
 * {@code InvokeAgentRuntime}으로 보낸다.
 *
 * <p>HTTP mode와 달라지는 것은 <b>transport뿐</b>이다 — 접수 body 필드·시각 포맷·회전 task token 계약,
 * ack의 동일 {@code taskId} + {@code PROCESSING} 검증, 미접수 확정 대 UNKNOWN 실패 분류는 같다.
 * AWS 호출·응답 파싱·분류는 {@link AgentCoreDispatchClient}가 소유한다.
 *
 * <p>⚠️ {@code taskToken}은 비밀 — 어떤 로그에도 포함하지 않는다(접수 payload로만 전송).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.ai.mode", havingValue = "agentcore")
@RequiredArgsConstructor
public class AgentCoreTimelineAiDispatcher implements TimelineAiDispatcher {

    /** runtime session id는 흐름 구분 prefix + taskId(UUID 36자)라 항상 33자 계약을 만족한다. */
    static final String SESSION_ID_PREFIX = "timeline-";

    private final AgentCoreDispatchClient dispatchClient;

    @Override
    public void dispatch(AiTimelineDispatchRequest request) {
        dispatchClient.dispatch(AgentCoreDispatchRequest.timeline(request), request.taskId(),
                SESSION_ID_PREFIX);
        log.info("timeline ai dispatch accepted(agentcore): taskId={}", request.taskId());
    }
}
