package com.laimory.server.common.privacy;

/**
 * 입력 text에서 치환이 확정된 [start, end) 구간.
 *
 * <p>{@code type == null}은 기존 placeholder literal 보호 구간이다 — 원문 그대로 유지하고
 * occurrence를 세지 않아 재적용 멱등성을 만든다.
 */
record RedactionSpan(int start, int end, RedactionType type) {

    boolean protectedLiteral() {
        return type == null;
    }
}
