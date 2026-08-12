package com.laimory.server.timeline.photo.migration;

/**
 * PHOTO subject migration 도구의 실행 모드(#284, 계획 §5.3·§5.6). {@code app.photo.migration.mode}
 * property 값과 1:1이며, 알 수 없는 값은 기동 실패로 fail-fast한다(조용한 no-op 금지).
 */
enum PhotoMigrationMode {

    /** legacy namespace S3 object를 subject key로 copy하고 존재·크기를 검증한다(계획 §5.3). */
    COPY_VERIFY("copy-verify"),

    /** staging/final PHOTO payload의 {@code photoUrl}을 legacy → subject URL로 rewrite한다(cutover window 전용). */
    REWRITE_URLS("rewrite-urls"),

    /** rollback — subject 기간 object를 legacy key로 copy한다(계획 §5.6). */
    REVERSE_COPY("reverse-copy"),

    /** rollback — {@code photoUrl}을 subject → legacy URL로 복원한다(계획 §5.6). */
    REVERSE_REWRITE("reverse-rewrite");

    private final String propertyValue;

    PhotoMigrationMode(String propertyValue) {
        this.propertyValue = propertyValue;
    }

    /**
     * property 값 → 모드. 매칭 실패는 {@link IllegalStateException}로 컨텍스트 기동을 실패시킨다
     * (모드 값은 운영자 설정이라 메시지에 포함해도 식별자 유출이 아니다).
     */
    static PhotoMigrationMode fromProperty(String value) {
        for (PhotoMigrationMode mode : values()) {
            if (mode.propertyValue.equals(value)) {
                return mode;
            }
        }
        throw new IllegalStateException("알 수 없는 app.photo.migration.mode: " + value);
    }
}
