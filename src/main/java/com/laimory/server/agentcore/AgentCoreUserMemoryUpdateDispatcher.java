package com.laimory.server.agentcore;

import com.laimory.server.agentcore.AgentCoreDispatchRequest;
import com.laimory.server.timeline.dto.AiUserMemoryUpdateRequest;
import com.laimory.server.timeline.service.UserMemoryUpdateDispatcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * AgentCore User Memory 디스패처({@code app.ai.mode=agentcore} — #338). 기존 접수 body를 그대로
 * {@code {"requestType":"USER_MEMORY_UPDATE","payload":{...}}} wrapper에 담아 Bedrock AgentCore
 * Runtime의 {@code InvokeAgentRuntime}으로 보낸다.
 *
 * <p>AgentCore Runtime은 endpoint 하나로 두 작업을 받으므로 HTTP mode의 경로 분기
 * ({@code /v1/user-memory})가 wrapper의 {@code requestType}으로 대체된다. 그 밖의 계약 —— 접수 body,
 * ack의 동일 {@code taskId} + {@code PROCESSING} 검증, 미접수 확정 대 UNKNOWN 분류, <b>재시도 없음</b> ——
 * 은 HTTP dispatcher와 같다.
 *
 * <p>⚠️ {@code taskToken}은 비밀 — 어떤 로그에도 포함하지 않는다(접수 payload로만 전송).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.ai.mode", havingValue = "agentcore")
@RequiredArgsConstructor
public class AgentCoreUserMemoryUpdateDispatcher implements UserMemoryUpdateDispatcher {

    /** runtime session id는 흐름 구분 prefix + taskId(UUID 36자)라 항상 33자 계약을 만족한다. */
    static final String SESSION_ID_PREFIX = "user-memory-";

    private final AgentCoreDispatchClient dispatchClient;

    @Override
    public void dispatch(AiUserMemoryUpdateRequest request) {
        dispatchClient.dispatch(AgentCoreDispatchRequest.userMemoryUpdate(request), request.taskId(),
                SESSION_ID_PREFIX);
        log.info("user memory update dispatch accepted(agentcore): taskId={}", request.taskId());
    }
}
