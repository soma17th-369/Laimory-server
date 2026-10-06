package com.laimory.server.credit;

/**
 * 크레딧을 소비하는 작업별 비용 카탈로그(#555) — 비용의 단일 기준이다. 앱 고지(공개 비용 조회 API), 소비 전
 * 사전 검사, 결과 저장 차감이 전부 이 값을 참조한다. 앱이 비용을 하드코딩하지 않으므로 값을 바꾸는 데는 서버
 * 배포만 필요하다.
 */
public enum CreditCost {

    /** 타임라인 1회 생성(AI 결과 저장 1회). */
    TIMELINE_CREATION(1);

    private final int amount;

    CreditCost(int amount) {
        this.amount = amount;
    }

    public int amount() {
        return amount;
    }
}
