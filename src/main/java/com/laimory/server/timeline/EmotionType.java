package com.laimory.server.timeline;

/**
 * 하루 전체를 대표하는 감정(5단계). draft 생성 흐름에선 설정하지 않고(NULL),
 * save(DRAFT->SAVED) 요청 body가 필수로 받아 SAVED 전이와 같은 조건부 UPDATE로 최초 확정한다.
 * 확정 후에는 SAVED 전용 감정 수정 PUT(PUT .../daily-records/{recordDate}/emotion)이 교체한다.
 * 저장 전 DRAFT와 과거 SAVED 행의 NULL은 정상값이며 이벤트별 감정은 모델링하지 않는다.
 */
public enum EmotionType {
    VERY_HAPPY,
    HAPPY,
    NEUTRAL,
    UNHAPPY,
    VERY_UNHAPPY
}
