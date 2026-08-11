package com.laimory.server.common.privacy;

/**
 * v1 redaction 유형과 출력 token literal의 단일 정의.
 *
 * <p>token literal은 저장·AI 전달 경계가 그대로 공유하는 계약 문자열이라 여기서만 소유한다.
 */
public enum RedactionType {
    PHONE("[REDACTED_PHONE]"),
    EMAIL("[REDACTED_EMAIL]"),
    RRN("[REDACTED_RRN]"),
    FOREIGNER_ID("[REDACTED_FOREIGNER_ID]"),
    PASSPORT("[REDACTED_PASSPORT]"),
    DRIVER_LICENSE("[REDACTED_DRIVER_LICENSE]"),
    CARD("[REDACTED_CARD]"),
    ACCOUNT("[REDACTED_ACCOUNT]"),
    SECRET("[REDACTED_SECRET]"),
    SOCIAL_ID("[REDACTED_SOCIAL_ID]"),
    /**
     * text detector가 없는 token 상수 전용 유형. AI input projection이 PHOTO {@code clientPhotoUri}
     * 필드 값 전체를 이 token으로 바꿀 때만 사용한다(자동 탐지 대상이 아니다).
     */
    DEVICE_URI("[REDACTED_DEVICE_URI]");

    private final String token;

    RedactionType(String token) {
        this.token = token;
    }

    public String token() {
        return token;
    }
}
