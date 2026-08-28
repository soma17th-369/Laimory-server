package com.laimory.server.timeline.service;

import com.laimory.server.timeline.dto.TimelineAiTestResponse;

/**
 * service → controller 전달값. 응답 body와, 헤더로만 나가는 {@code X-Timeline-Timed-Out} 여부를 함께 담는다
 * (헤더는 body 계약을 건드리지 않으려는 선택이라 DTO 안에 넣지 않는다).
 */
public record TimelineAiTestOutcome(TimelineAiTestResponse response, boolean timedOut) {
}
