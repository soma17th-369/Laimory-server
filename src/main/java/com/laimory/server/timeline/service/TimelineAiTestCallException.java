package com.laimory.server.timeline.service;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import lombok.Getter;

/**
 * dev 전용 AI 동기 테스트 호출 실패 — 기존 {@code AI_DISPATCH_FAILED}(502 {@code -1009})로 나간다.
 * 이 경로 때문에 공개 error code를 늘리지 않는다.
 *
 * <p>AI가 실제로 {@code {errorCode, error}}를 준 경우 그 <b>numeric code만</b> 담는다 — 호출자에게는
 * {@code X-Ai-Error-Code} 헤더로 나가고, 자유 text {@code error}는 사용자 원문이 섞일 수 있어 담지도
 * 로그하지도 않는다. AI 응답 자체가 없었던 실패(timeout·connect 실패·전송 오류)는 {@code aiStatus}·
 * {@code aiErrorCode}가 모두 {@code null}이라, 헤더 유무가 곧 "AI가 답을 하긴 했는가"의 신호가 된다.
 *
 * <p>{@code reason}은 서버 로그 전용 진단 문구다 — AI 응답 원문·사용자 데이터·token을 담지 않는다.
 */
@Getter
public class TimelineAiTestCallException extends BusinessException {

    private final String reason;

    /** AI HTTP status(응답을 받은 경우). 응답이 없으면 {@code null}. */
    private final transient Integer aiStatus;

    /** AI가 body로 준 numeric errorCode. 없거나 파싱 불가면 {@code null}. */
    private final transient Integer aiErrorCode;

    public TimelineAiTestCallException(String reason, Integer aiStatus, Integer aiErrorCode) {
        super(ExceptionType.AI_DISPATCH_FAILED);
        this.reason = reason;
        this.aiStatus = aiStatus;
        this.aiErrorCode = aiErrorCode;
    }
}
