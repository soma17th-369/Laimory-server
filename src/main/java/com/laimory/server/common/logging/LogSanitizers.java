package com.laimory.server.common.logging;

/**
 * 로그에 싣는 외부/사용자 유래 문자열의 안전 처리.
 *
 * <p>AI 콜백의 자유 텍스트나 사용자 입력(content-type 등)은 길이·내용물을 통제할 수 없으므로,
 * 로그에 남길 때는 절단해 로그 폭주와 민감 조각(URL·토큰) 유입을 제한한다.
 */
public final class LogSanitizers {

    private LogSanitizers() {
    }

    /** value를 최대 {@code max}자로 절단한다(초과 시 {@code ...} 접미). null은 null 그대로. */
    public static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max) + "...";
    }
}
