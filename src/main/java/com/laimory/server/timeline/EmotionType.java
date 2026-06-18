package com.laimory.server.timeline;

/**
 * 하루 전체를 대표하는 감정(5단계). draft 생성 흐름에선 설정하지 않고(NULL),
 * 별도 save(DRAFT->SAVED) 흐름에서 설정한다. 카드별 감정은 MVP에 없다.
 */
public enum EmotionType {
    VERY_HAPPY,
    HAPPY,
    NEUTRAL,
    UNHAPPY,
    VERY_UNHAPPY
}
