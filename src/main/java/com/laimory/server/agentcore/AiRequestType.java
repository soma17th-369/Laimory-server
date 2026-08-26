package com.laimory.server.agentcore;

/**
 * AgentCore 접수 wrapper가 싣는 요청 종류({@code app.ai.mode=agentcore} — #338).
 *
 * <p>AgentCore Runtime은 endpoint 하나로 두 작업을 모두 받으므로(HTTP mode의 {@code /v1/timeline}·
 * {@code /v1/user-memory} 경로 분기가 없다) AI Server는 이 값으로 payload를 기존 Timeline 또는 User
 * Memory 접수 DTO로 역직렬화·라우팅한다. <b>양 저장소가 문자열까지 고정하는 공개 계약</b>이라 상수
 * 이름을 바꾸지 않는다.
 */
public enum AiRequestType {

    /** payload가 {@link AiTimelineDispatchRequest} — 타임라인 이벤트 생성 접수. */
    TIMELINE,

    /** payload가 {@link AiUserMemoryUpdateRequest} — User Memory 갱신 접수. */
    USER_MEMORY_UPDATE
}
