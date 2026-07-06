package com.laimory.server.timeline;

/**
 * HEALTH 아이템의 건강 지표 종류. value의 단위는 지표가 결정한다 —
 * STEPS=걸음 수(보), DISTANCE=이동 거리(미터), SLEEP=수면 시간(분).
 */
public enum HealthMetric {
    STEPS,
    DISTANCE,
    SLEEP
}
