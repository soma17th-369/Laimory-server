package com.laimory.server.user.migration;

/**
 * subject backfill migration 도구의 실행 모드(#285, 계획 §5.3~§5.4). {@code app.subject.migration.mode}
 * property 값과 1:1이며, 알 수 없는 값은 기동 실패로 fail-fast한다(조용한 no-op 금지 —
 * {@code PhotoMigrationMode}와 같은 형태).
 *
 * <p>역방향(rollback) 모드는 지원하지 않는다 — cutover는 forward 전용이며 legacy 컬럼·테이블은
 * 검증 후 별도 승인 하에 즉시 삭제한다(#285 runbook).
 */
enum SubjectMigrationMode {

    /** {@code users} 전 행을 순회해 아직 mapping이 없는 사용자의 subject mapping을 멱등 생성한다. */
    BACKFILL_MAPPINGS("backfill-mappings"),

    /**
     * DailyRecord/staging/push registration의 NULL {@code subject_id}를 각 행의 user_id → mapping
     * subject로 채우고, {@code user_memories}를 {@code user_memory_documents}로 upsert 복사한 뒤
     * 잔여 NULL·cross-owner 0건과 문서 subject·JSON·감사 컬럼 동등성을 검증한다
     * (불일치 fail-closed).
     */
    BACKFILL_OWNERS("backfill-owners"),

    /** backfill 없이 {@link #BACKFILL_OWNERS}의 종료 검증만 다시 수행한다(delta 재검증용). */
    VERIFY_OWNERS("verify-owners");

    private final String propertyValue;

    SubjectMigrationMode(String propertyValue) {
        this.propertyValue = propertyValue;
    }

    /**
     * property 값 → 모드. 매칭 실패는 {@link IllegalStateException}로 컨텍스트 기동을 실패시킨다
     * (모드 값은 운영자 설정이라 메시지에 포함해도 식별자 유출이 아니다).
     */
    static SubjectMigrationMode fromProperty(String value) {
        for (SubjectMigrationMode mode : values()) {
            if (mode.propertyValue.equals(value)) {
                return mode;
            }
        }
        throw new IllegalStateException("알 수 없는 app.subject.migration.mode: " + value);
    }
}
