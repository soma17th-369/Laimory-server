package com.laimory.server.timeline;

/**
 * 일일 기록 상태. DRAFT(작성중) -> SAVED(작성완료).
 * draft 생성 흐름은 신규 record를 DRAFT로 만들고, SAVED는 별도 save 흐름에서 전환한다.
 */
public enum DailyRecordStatus {
    DRAFT,
    SAVED
}
