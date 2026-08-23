package com.laimory.server.common.logging;

/**
 * 외부에서 유입된 자유 문자열을 로그에 넣기 전에 정화하는 단일 지점.
 *
 * <p>CR/LF 제거(로컬 텍스트 로그 라인 위조 방지)와 길이 상한을 적용한다. 길이 상한은 로그 비대를
 * 막고, keyword 필드로 색인되는 값이 Lucene 단일 term 한도(32,766B)를 넘어 <b>문서 전체가 ES에서
 * 거부</b>되는 사고를 소스에서 차단한다(매핑의 {@code ignore_above}와 이중 방어).
 *
 * <p>대상은 요청·응답·외부 시스템에서 온 자유 문자열뿐이다 — 서버 생성 값(id·count·enum)은
 * 유계라 통과시킬 필요 없다.
 */
public final class LogSanitizer {

    private LogSanitizer() {
    }

    /** CR/LF를 공백으로 치환하고 maxLength자로 자른다(절단 시 말줄임 표시). null은 null 그대로. */
    public static String sanitize(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String cleaned = value.replace('\r', ' ').replace('\n', ' ');
        return cleaned.length() <= maxLength ? cleaned : cleaned.substring(0, maxLength - 1) + "…";
    }
}
