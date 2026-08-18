package com.laimory.server.push;

/** 알림 법적 수신 동의의 종류. 두 동의는 서로 분리된 선택 동의이며 미리 체크하지 않는다. */
public enum NotificationConsentType {

    /** 광고성 정보 수신 동의. 없으면 {@code ADVERTISING} 분류 푸시를 전혀 보내지 않는다. */
    ADVERTISING_PUSH,

    /** 야간(21:00~08:00 KST) 광고성 정보 수신 동의. 일반 광고 동의가 있어야만 켤 수 있다. */
    NIGHT_ADVERTISING_PUSH
}
