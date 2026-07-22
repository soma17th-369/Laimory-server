package com.laimory.server.timeline.service;

import com.laimory.server.timeline.dto.AiTimelineDispatchRequest;

/**
 * 타임라인 이벤트 생성을 외부 AI에 위임한다(direct-write 계약).
 *
 * <p>AI는 접수(202) 후 background에서 inference를 수행하고, 생성 결과 validation과 final
 * Event/Item/junction 저장·accepted source 삭제를 자신의 DB transaction으로 직접 commit한 뒤
 * {@code {status,errorCode,error}} callback으로 상태만 알린다({@code Callback-Token} header에 접수 body로
 * 받은 원문 token을 그대로 반환). 서버는 결과 graph를 다시 조립·검증·저장하지 않는다.
 *
 * <p>{@code dispatch}는 접수 확인까지 동기다 — AI의 202는 schema 수락 즉시 반환되므로 요청 스레드가
 * LLM inference를 기다리지 않는다. 접수 실패(비202·shape 불일치·타임아웃)는 RuntimeException으로 던지고,
 * 호출부가 task를 FAILED로 종결한다.
 *
 * <p>구현 선택은 {@code app.ai.mode}: {@code noop}(기본 — AI 미연동, prod 안전) /
 * {@code fake}(dev — in-process fake가 AI direct-write를 대행) / {@code http}(실 AI HTTP 연동).
 * ⚠️ {@code request.callbackToken}은 비밀이므로 어떤 구현도 절대 로그하지 않는다.
 */
public interface TimelineAiDispatcher {

    void dispatch(AiTimelineDispatchRequest request);
}
