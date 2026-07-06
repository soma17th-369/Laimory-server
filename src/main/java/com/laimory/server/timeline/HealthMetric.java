package com.laimory.server.timeline;

/**
 * HEALTH 아이템의 건강 지표 종류. 값 필드는 지표가 결정한다 —
 * STEPS=걸음 수(value, 보), DISTANCE=이동 거리(value, 미터), SLEEP=수면 시간(durationMinutes, 분).
 */
public enum HealthMetric {
    STEPS,
    DISTANCE,
    SLEEP
}
