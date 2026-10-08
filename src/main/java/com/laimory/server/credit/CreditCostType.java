package com.laimory.server.credit;

/**
 * 크레딧을 소비하는 기능 종류(#555). 비용 값은 DB {@code credit_costs}의 이 종류 행이 단일 기준이다(#558) —
 * 앱 고지(공개 비용 조회 API), 소비 전 사전 검사, 결과 저장 차감이 전부 그 행을 읽는다. 값 변경은 새 Flyway
 * migration의 UPDATE로만 한다. 종류를 추가할 때는 같은 PR의 migration에 seed 행을 함께 넣는다.
 */
public enum CreditCostType {

    /** 타임라인 1회 생성(AI 결과 저장 1회). */
    TIMELINE_CREATION
}
