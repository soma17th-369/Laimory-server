package com.laimory.server.credit;

/**
 * 크레딧 사용 기록의 상태(#565). {@link #CHARGED}에서 {@link #CONSUMED} 또는 {@link #REFUNDED}로 한 번 전이한다.
 * 유실 환불과 수동 환불 조회는 {@link #CHARGED}로 남은 행을 대상으로 한다.
 */
public enum CreditHistoryStatus {

    /** 요청 시 차감됐고 아직 결과가 정해지지 않았다. */
    CHARGED,

    /** 결과가 저장돼 차감이 확정됐다. */
    CONSUMED,

    /** 생성이 실패해 차감액을 돌려줬다. */
    REFUNDED
}
