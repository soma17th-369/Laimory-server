package com.laimory.server.terms;

/**
 * 약관 노출·동의 단계. 앱 화면 분기와 서버 enforcement의 공통 축이다.
 *
 * <ul>
 *   <li>{@link #LOGIN} — 로그인 직후 동의 화면. 필수 미동의는 {@code /a/api} 대부분을 403으로 막는다.</li>
 *   <li>{@link #TIMELINE_FIRST_CREATE} — 최초 타임라인 생성(사진 presign·draft 생성) 직전 동의 화면.</li>
 *   <li>{@link #PUSH_SETTINGS} — 푸시 설정 화면의 선택 동의(광고성·야간 광고성 수신). 필수 문서가 없어
 *       enforcement gate를 갖지 않는다 — 문서는 동의 화면 문구·버전 catalog로만 쓰이고 동의 상태는
 *       전용 push 동의 API가 소유한다(#314).</li>
 * </ul>
 */
public enum TermStage {
    LOGIN,
    TIMELINE_FIRST_CREATE,
    PUSH_SETTINGS
}
