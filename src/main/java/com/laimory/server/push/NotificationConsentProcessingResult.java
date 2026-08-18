package com.laimory.server.push;

/**
 * 동의·철회 요청의 처리 결과 — 사용자에게 즉시 통지하고 증적에도 보존한다.
 *
 * <p>상태가 이미 같아 바꿀 것이 없었어도 "처리하지 않음"이 아니라 {@link #ALREADY_IN_STATE}로 결과를
 * 돌려준다. 사용자 의사 표시는 언제나 처리 결과를 받는다.
 */
public enum NotificationConsentProcessingResult {

    /** 요청대로 현재 동의 상태를 바꿨다(문서 버전 갱신 포함). */
    APPLIED,

    /** 이미 요청한 상태였다 — 상태·시각을 덮어쓰지 않았다. */
    ALREADY_IN_STATE
}
