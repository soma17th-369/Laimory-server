package com.laimory.server.timeline.dto;

/**
 * API→AI {@code POST /v1/timeline} 접수 body — 양 저장소가 contract fixture로 고정하는 공개 계약이다.
 * 필드명을 임의로 바꾸지 않는다(명명 권위는 AI 규격).
 *
 * <p>task 입력 데이터는 body에 싣지 않는다 — AI가 {@code taskId}와 이 토큰으로 서버간 입력 조회 API를
 * 호출해 받아간다. {@code dailyRecordId}·{@code window}를 보내지 않는 이유이기도 하다(AI는 DB 식별자를
 * 알 필요가 없고, window는 입력 응답에 있다).
 *
 * <p>{@code taskToken}은 단계별 토큰 chain의 첫 토큰(T1) 원문으로 이 body로만 한 번 전달된다
 * (로그·MySQL·Redis 저장 금지 — 서버는 hash만 보관).
 */
public record AiTimelineDispatchRequest(
        String taskId,
        String taskToken
) {
}
