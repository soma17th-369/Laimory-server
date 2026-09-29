package com.laimory.server.inquiry;

import java.time.LocalDateTime;

/**
 * 앱에 보이는 문의 처리 상태(#529). 저장 컬럼이 아니라 {@code answered_at}에서 파생한다 — 판정 규칙을
 * 서버 한 곳에 두어 클라이언트가 {@code answeredAt} null 여부로 따로 해석하지 않게 한다.
 *
 * <p>관리자가 처리됨 표시를 해제하면 {@code ANSWERED}에서 {@code RECEIVED}로 되돌아간다(단조 증가 아님).
 */
public enum InquiryStatus {
    /** 접수됨 — 관리자가 아직 처리됨으로 표시하지 않았다. */
    RECEIVED,
    /** 답변 완료 — 관리자가 입력 이메일로 답장한 뒤 처리됨으로 표시했다. */
    ANSWERED;

    public static InquiryStatus of(LocalDateTime answeredAt) {
        return answeredAt == null ? RECEIVED : ANSWERED;
    }
}
