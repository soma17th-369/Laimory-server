package com.laimory.server.user;

/**
 * 회원 상태(#305). {@code ACTIVE → WITHDRAWAL_PENDING} 단방향 전이만 있다 —
 * {@code WITHDRAWAL_PENDING} 행을 {@code ACTIVE}로 되돌리는 경로는 만들지 않는다(재가입은 새 행).
 *
 * <p>#302 물리 삭제 완료 뒤의 {@code WITHDRAWN} 보존 또는 행 삭제는 그 계획에서 확정한다.
 */
public enum UserStatus {

    /** 일반 활성 회원 — {@code /a/api} 인증과 token/refresh 발급이 허용되는 유일한 상태. */
    ACTIVE,

    /** 탈퇴 접수됨 — 모든 인증·발급 경로에서 회원 없음과 구분 없이 거절되며, 데이터 삭제는 #302 worker 몫. */
    WITHDRAWAL_PENDING
}
