package com.laimory.server.terms;

/**
 * 약관 노출·동의 단계. 앱 화면 분기와 서버 enforcement의 공통 축이다.
 *
 * <ul>
 *   <li>{@link #LOGIN} — 로그인 직후 동의 화면. 필수 미동의는 {@code /a/api} 대부분을 403으로 막는다.</li>
 *   <li>{@link #TIMELINE_FIRST_CREATE} — 최초 타임라인 생성(사진 presign·draft 생성) 직전 동의 화면.</li>
 * </ul>
 */
public enum TermStage {
    LOGIN,
    TIMELINE_FIRST_CREATE
}
