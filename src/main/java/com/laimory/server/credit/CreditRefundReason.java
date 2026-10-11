package com.laimory.server.credit;

/** 크레딧 환불 사유(#565). 환불한 경로를 기록에 남겨 운영 조회가 원인을 구분하게 한다. */
public enum CreditRefundReason {

    /** AI가 FAILED callback으로 실패를 보고했다. */
    AI_FAILED,

    /** 결과·실패 보고 없이 처리 시간이 지나 task가 유실됐다. */
    TIMEOUT,

    /** AI가 dispatch를 거절했다(미접수 확정). */
    DISPATCH_REJECTED,

    /** 차감 뒤 타이머·PROCESSING task 저장이 실패해 생성을 시작하지 못했다. */
    TASK_SAVE_FAILED
}
