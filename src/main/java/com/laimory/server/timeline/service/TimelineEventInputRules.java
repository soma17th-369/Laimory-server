package com.laimory.server.timeline.service;

import java.time.LocalDateTime;

/**
 * 사용자 Event 입력의 공통 상세 규칙(title·subtitle·시간 범위·memo) — Event PATCH/memo PUT
 * ({@link TimelineEventEditService})과 수동 Event 생성({@link TimelineEventCreateService})이 공유해
 * 같은 필드 규칙이 갈라지지 않게 한다. DTO의 Bean Validation은 생성 필수값만 담당하며,
 * 정규화 후 길이·시간 관계는 이 규칙이 소유한다. PATCH의 기존 값 병합·변경 여부·PHOTO 검증은 각 서비스가 소유한다.
 */
final class TimelineEventInputRules {

    static final int MAX_TITLE_LENGTH = 255;
    static final int MAX_SUBTITLE_LENGTH = 255;
    /** User Memory 갱신 접수 계약이 확정한 상한. 초과 memo는 AI가 422로 거절하므로 입력에서 막는다. */
    static final int MAX_MEMO_LENGTH = 500;

    private TimelineEventInputRules() {
    }

    /** null·공백뿐은 거절, 그 외 strip 후 최대 255자. */
    static String requireValidTitle(String title) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title is required");
        }
        String stripped = title.strip();
        if (stripped.length() > MAX_TITLE_LENGTH) {
            throw new IllegalArgumentException("title is too long: length=" + stripped.length());
        }
        return stripped;
    }

    /** null·공백뿐은 null(비움), 그 외 strip 후 최대 255자. */
    static String normalizeSubtitle(String subtitle) {
        if (subtitle == null || subtitle.isBlank()) {
            return null;
        }
        String stripped = subtitle.strip();
        if (stripped.length() > MAX_SUBTITLE_LENGTH) {
            throw new IllegalArgumentException("subtitle is too long: length=" + stripped.length());
        }
        return stripped;
    }

    /** startAt은 필수, endAt은 nullable이되 값이 있으면 startAt보다 이전일 수 없다. */
    static void requireValidTimeRange(LocalDateTime startAt, LocalDateTime endAt) {
        if (startAt == null) {
            throw new IllegalArgumentException("startAt is required");
        }
        if (endAt != null && endAt.isBefore(startAt)) {
            throw new IllegalArgumentException("endAt is before startAt");
        }
    }

    /** null·공백뿐은 null(제거), 그 외 trim 없이 원문 최대 500자({@code String.length()} 기준). */
    static String normalizeMemo(String memo) {
        if (memo == null || memo.isBlank()) {
            return null;
        }
        if (memo.length() > MAX_MEMO_LENGTH) {
            throw new IllegalArgumentException("memo is too long: length=" + memo.length());
        }
        return memo;
    }
}
