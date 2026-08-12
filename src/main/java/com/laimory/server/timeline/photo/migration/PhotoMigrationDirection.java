package com.laimory.server.timeline.photo.migration;

/** copy/rewrite 실행 방향 — forward(legacy→subject)와 rollback(subject→legacy)이 같은 구현을 공유한다. */
enum PhotoMigrationDirection {

    LEGACY_TO_SUBJECT,

    SUBJECT_TO_LEGACY
}
