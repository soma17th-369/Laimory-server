package com.laimory.server.timeline.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
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

    /** 해석할 수 없거나 계약에 맞지 않으면 {@link IllegalArgumentException}이다. */
    static TimelineDispatchMessage decode(ObjectMapper objectMapper, String body) {
        TimelineDispatchMessage message;
        try {
            // Jackson 기본값은 OffsetDateTime을 UTC로 바꿔 읽는다. window는 POST 시점의 record timezone offset이 계약
            // (AI 접수 body의 "+09:00" 형식)이라 offset을 보존해 읽는다.
            message = objectMapper.readerFor(TimelineDispatchMessage.class)
                    .without(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
                    .readValue(body);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("TIMELINE 대기열 메시지를 해석할 수 없습니다");
        }
        boolean valid = message != null
                && REQUEST_TYPE.equals(message.requestType())
                && message.taskId() != null && !message.taskId().isBlank()
                && message.dailyRecordId() > 0
                && message.window() != null
                && message.window().startAt() != null && message.window().endAt() != null;
        if (!valid) {
            throw new IllegalArgumentException("TIMELINE 대기열 메시지 계약 불일치");
        }
        return message;
    }
}
