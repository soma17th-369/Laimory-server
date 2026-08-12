package com.laimory.server.timeline.photo.migration;

/**
 * migration fail-closed 중단 신호. <b>메시지는 건수(count)만 담는다</b> — raw userId, HMAC, subject,
 * object key, URL, JSON 값을 절대 포함하지 않는다(계획 §5.3 로그 불변식). runner는 이 메시지만
 * 로그로 남기고 비정상 exit한다.
 */
class PhotoMigrationAbortedException extends RuntimeException {

    PhotoMigrationAbortedException(String countOnlyMessage) {
        super(countOnlyMessage);
    }
}
