package com.laimory.server.common;

import java.util.regex.Pattern;

/**
 * API 경로의 버전 세그먼트({@link ApiUrls#VERSION}, 예: {@code v1}). service가 버전별 동작을 문자열 비교 대신
 * 대소 비교로 분기하도록 숫자로 읽는다.
 */
public record ApiVersion(int number) {

    private static final Pattern FORMAT = Pattern.compile("v\\d+");

    /** {@code v<숫자>} 형식만 받는다. 경로 정규식을 통과해도 int 범위를 넘으면 거절한다. */
    public static ApiVersion of(String value) {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid api version: " + value);
        }
        return new ApiVersion(Integer.parseInt(value.substring(1)));
    }

    public boolean isLowerThan(ApiVersion other) {
        return number < other.number;
    }
}
