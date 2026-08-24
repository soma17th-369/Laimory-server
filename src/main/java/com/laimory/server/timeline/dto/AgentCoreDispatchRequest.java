package com.laimory.server.timeline.dto;

import java.util.Objects;

/**
 * API→AgentCore Runtime 접수 wrapper({@code app.ai.mode=agentcore} — #338).
 *
 * <pre>{@code {"requestType":"TIMELINE","payload":{ ...기존 접수 body 그대로... }}}</pre>
 *
 * <p><b>payload는 기존 DTO 인스턴스를 그대로 담는다</b> — 필드명·시각 포맷·{@code taskToken} 계약은
 * HTTP mode와 byte 단위로 같아야 하므로 여기서 필드를 재배치하거나 복제하지 않는다. 직렬화도 Spring
 * Boot가 구성한 {@code ObjectMapper}(JavaTimeModule 등록본)로만 한다 — {@code new ObjectMapper()}를
 * 쓰면 {@code OffsetDateTime}이 계약과 다른 포맷으로 나간다.
 *
 * <p>{@code requestType}은 AI Server의 역직렬화 분기 키다({@link AiRequestType}). wrapper는 transport
 * 계층 봉투일 뿐이라 payload 안의 계약에는 관여하지 않는다.
 *
 * <p>⚠️ payload의 {@code taskToken}은 비밀 — 이 wrapper 전체를 로그·trace·예외 메시지에 담지 않는다
 * (AgentCore 요청 body로만 전송).
 */
public record AgentCoreDispatchRequest<T>(AiRequestType requestType, T payload) {

    public AgentCoreDispatchRequest {
        Objects.requireNonNull(requestType, "requestType");
        Objects.requireNonNull(payload, "payload");
    }

    /** 타임라인 생성 접수 body를 wrapper에 담는다. */
    public static AgentCoreDispatchRequest<AiTimelineDispatchRequest> timeline(
            AiTimelineDispatchRequest payload) {
        return new AgentCoreDispatchRequest<>(AiRequestType.TIMELINE, payload);
    }

    /** User Memory 갱신 접수 body를 wrapper에 담는다. */
    public static AgentCoreDispatchRequest<AiUserMemoryUpdateRequest> userMemoryUpdate(
            AiUserMemoryUpdateRequest payload) {
        return new AgentCoreDispatchRequest<>(AiRequestType.USER_MEMORY_UPDATE, payload);
    }
}
