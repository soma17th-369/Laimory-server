package com.laimory.server.agentcore;

import com.laimory.server.agentcore.AgentCoreDispatchRequest;
import com.laimory.server.timeline.dto.AiTimelineDispatchRequest;
import com.laimory.server.timeline.service.TimelineAiDispatcher;
import java.time.Duration;
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

    /**
     * 접수 ack 대기 상한(#600). 사용자 요청 스레드에서 기다리므로 짧게 둔다 — 앱의 draft POST 상한(OkHttp 기본 read
     * timeout 10초) 안에 dispatch 전 작업(geocoding enrich 등)까지 넣어야 한다. 넘겨서 끊겨도 접수 결과 불명으로
     * 202를 돌려주므로 사용자 결과는 같다.
     */
    public static final Duration ACK_ATTEMPT_TIMEOUT = Duration.ofSeconds(5);
    /** SDK 재시도가 없어 사실상 단일 시도다. 자격증명 해석 등 시도 밖 시간까지 포함한 상한. */
    public static final Duration ACK_CALL_TIMEOUT = Duration.ofSeconds(7);

    private final AgentCoreDispatchClient dispatchClient;

    @Override
    public void dispatch(AiTimelineDispatchRequest request) {
        dispatchClient.dispatch(AgentCoreDispatchRequest.timeline(request), request.taskId(),
                SESSION_ID_PREFIX, ACK_ATTEMPT_TIMEOUT, ACK_CALL_TIMEOUT);
        log.info("timeline ai dispatch accepted(agentcore): taskId={}", request.taskId());
    }
}
