package com.laimory.server.timeline.photo.migration;

/**
 * PHOTO subject migration 도구의 실행 모드(#284, 계획 §5.3). {@code app.photo.migration.mode}
 * property 값과 1:1이며, 알 수 없는 값은 기동 실패로 fail-fast한다(조용한 no-op 금지).
 *
 * <p>역방향(rollback) 모드는 지원하지 않는다 — cutover 후 legacy object는 별도 승인 하에 즉시
 * 삭제한다(#285 runbook).
 */
enum PhotoMigrationMode {

    /** legacy namespace S3 object를 subject key로 copy하고 존재·크기를 검증한다(계획 §5.3). */
    COPY_VERIFY("copy-verify"),

    /** staging/final PHOTO payload의 {@code photoUrl}을 legacy → subject URL로 rewrite한다(cutover window 전용). */
    REWRITE_URLS("rewrite-urls"),

    /** target 동등성을 재검증한 뒤 모든 known-user legacy object를 삭제하고 잔여 0건을 확인한다. */
    DELETE_LEGACY("delete-legacy");

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
