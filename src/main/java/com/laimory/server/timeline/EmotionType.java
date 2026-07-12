package com.laimory.server.timeline;

/**
 * 하루 전체를 대표하는 감정(5단계). draft 생성 흐름에선 설정하지 않고(NULL),
 * 향후 save(DRAFT->SAVED) 흐름에서 설정한다. 현재 save 흐름은 없으며 이벤트별 감정도 모델링하지 않는다.
 */
public enum EmotionType {
    VERY_HAPPY,
    HAPPY,
    NEUTRAL,
    UNHAPPY,
    VERY_UNHAPPY
}
