package com.laimory.server.user;

/**
 * 계정 삭제 작업 상태(#305). 이번 범위에는 durable 접수 권위인 {@code PENDING}만 있다 —
 * worker claim/stage 상태는 #302가 additive migration으로 확장한다.
 */
public enum AccountErasureJobStatus {

    /** 탈퇴 transaction이 접수한 미처리 삭제 요청 — #302 worker가 소비할 때까지 유지된다. */
    PENDING
}
