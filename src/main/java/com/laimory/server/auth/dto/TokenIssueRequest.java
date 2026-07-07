package com.laimory.server.auth.dto;

/**
 * 토큰 발급 요청. appCode는 로그인 딥링크로 받은 일회용 코드, appVerifier는 앱이 로그인 시작 전
 * 생성해 보관한 비밀값(app_challenge의 원문 — 핸드오프 PKCE).
 */
public record TokenIssueRequest(
        String appCode,
        String appVerifier
) {
}
