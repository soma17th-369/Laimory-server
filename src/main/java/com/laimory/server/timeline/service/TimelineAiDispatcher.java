package com.laimory.server.timeline.service;

import com.laimory.server.timeline.dto.AiTimelineDispatchRequest;

/**
 * 타임라인 이벤트 생성을 외부 AI에 위임한다.
 *
 * <p>AI는 접수(202) 후 background에서 서버간 입력 API로 정규 입력을 조회하고, inference 결과를 결과
 * 저장 API에 보낸 뒤 status-only callback을 호출한다. 세 요청은 dispatch body로 받은 같은
 * {@code Task-Token}을 사용하고 API 서버가 final graph transaction을 소유한다.
 *
 * <p>{@code dispatch}는 접수 확인까지 동기다 — AI의 202는 schema 수락 즉시 반환되므로 요청 스레드가
 * LLM inference를 기다리지 않는다. 접수 실패는 두 갈래다: 4xx 거절은
 * {@link TimelineAiDispatchRejectedException}(미접수 확정 — 호출부가 FAILED 종결을 시도),
 * 그 외 예외(타임아웃·전송 실패·5xx·비202/계약 불일치)는 접수 여부 불명(UNKNOWN)으로 그대로 전파해
 * 호출부가 PROCESSING을 유지한다. 어느 경우든 호출부는 draft POST를 502(-1009)로 실패시킨다.
 *
 * <p>구현 선택은 {@code app.ai.mode}: {@code noop}(기본 — AI 미연동, prod 안전) /
 * {@code fake}(dev — in-process fake가 서버간 계약을 호출) / {@code http}(실 AI HTTP 연동) /
 * {@code agentcore}(실 AI 연동 — Bedrock AgentCore Runtime에 공통 wrapper로 접수, #338).
 * ⚠️ {@code request.taskToken}은 비밀이므로 어떤 구현도 절대 로그하지 않는다.
 */
public interface TimelineAiDispatcher {

    void dispatch(AiTimelineDispatchRequest request);
}
