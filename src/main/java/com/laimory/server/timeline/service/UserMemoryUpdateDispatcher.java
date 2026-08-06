package com.laimory.server.timeline.service;

import com.laimory.server.timeline.dto.AiUserMemoryUpdateRequest;

/**
 * User Memory 갱신을 외부 AI에 위임한다.
 *
 * <p>AI는 접수(202) 후 background에서 문서를 새로 만들고, 성공·실패 모두 결과 저장 API
 * ({@code POST /s/api/{v}/user-memory/updates/{taskId}/result})를 한 번 호출한다. 상태 조회 endpoint도
 * token 재발급도 없다 — 작업당 token 하나이고 그 호출이 결과 전달과 종료 통보를 겸한다.
 *
 * <p>{@code dispatch}는 접수 확인까지 동기지만 <b>요청 스레드가 아니라 worker 스레드에서</b> 실행된다
 * ({@link UserMemoryUpdateWorker}). 사용자의 저장은 이미 커밋돼 200으로 나갔다.
 *
 * <p>접수 실패는 두 갈래이고 draft dispatch와 같은 분류를 쓴다: 4xx 거절은
 * {@link TimelineAiDispatchRejectedException}(미접수 확정 — 호출부가 task를 지운다), 그 외 예외
 * (타임아웃·전송 실패·5xx·비202/계약 불일치)는 접수 여부 불명(UNKNOWN)으로 전파해 호출부가 task를
 * 남긴다(AI가 이미 받았을 수 있다). <b>어느 경우도 재시도하지 않는다</b> — 저장은 이미 끝났고 그 날치
 * memory 반영만 누락된다.
 *
 * <p>구현 선택은 {@code app.ai.mode}: {@code noop}(기본 — AI 미연동, prod 안전) /
 * {@code fake}(dev — in-process fake가 서버간 계약을 호출) / {@code http}(실 AI HTTP 연동).
 * ⚠️ {@code request.taskToken}은 비밀이므로 어떤 구현도 절대 로그하지 않는다.
 */
public interface UserMemoryUpdateDispatcher {

    void dispatch(AiUserMemoryUpdateRequest request);
}
