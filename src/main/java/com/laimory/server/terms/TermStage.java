package com.laimory.server.terms;

/**
 * 약관 노출·동의 단계. 기동 seed 검사({@code TermCatalogReadiness})의 축이다 — stage별 필수 종류의
 * current 문서 존재를 기동 시 경보한다(#436 이후 요청 경로 강제는 없다).
 *
 * <ul>
 *   <li>{@link #LOGIN} — 로그인 직후 동의 화면.</li>
 *   <li>{@link #TIMELINE_FIRST_CREATE} — 최초 타임라인 생성(사진 presign·draft 생성) 직전 동의 화면.</li>
 * </ul>
 */
public enum TermStage {
    LOGIN,
    TIMELINE_FIRST_CREATE
}
