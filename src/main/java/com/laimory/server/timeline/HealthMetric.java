package com.laimory.server.timeline;

/**
 * HEALTH 아이템의 건강 지표 종류 — STEPS=걸음 수, DISTANCE=이동 거리, SLEEP=수면 시간.
 * (단위는 value 텍스트 자체에 포함된다. 예: "100보", "140분")
 */
public enum HealthMetric {
    STEPS,
    DISTANCE,
    SLEEP
}
