package com.laimory.server.timeline.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.timeline.dto.AiTimelineDispatchRequest;

/**
 * TIMELINE 접수 대기열 메시지(#515). 식별자와 POST 시점의 offset window만 싣는다 — taskToken(비밀)·사용자 원문·source
 * item은 싣지 않는다. token은 worker가 접수 직전에 만들고, source는 AI가 그 token으로 입력 조회 API에서 가져간다.
 *
 * <pre>{"requestType":"TIMELINE","taskId":"…","dailyRecordId":123,"window":{"startAt":"…+09:00","endAt":"…+09:00"}}</pre>
 */
public record TimelineDispatchMessage(String requestType, String taskId, long dailyRecordId,
                                      AiTimelineDispatchRequest.Window window) {

    /** AgentCore wrapper의 requestType과 같은 값이다. */
    static final String REQUEST_TYPE = "TIMELINE";

    static TimelineDispatchMessage of(String taskId, long dailyRecordId, AiTimelineDispatchRequest.Window window) {
        return new TimelineDispatchMessage(REQUEST_TYPE, taskId, dailyRecordId, window);
    }

    String encode(ObjectMapper objectMapper) {
        try {
            return objectMapper.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            // 식별자와 시각뿐이라 일어나지 않아야 한다. 메시지 원문을 예외에 싣지 않는다.
            throw new IllegalStateException("TIMELINE 대기열 메시지를 직렬화하지 못했습니다: taskId=" + taskId);
        }
    }
}
