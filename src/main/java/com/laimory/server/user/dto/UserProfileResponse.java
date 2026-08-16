package com.laimory.server.user.dto;

/**
 * 내 회원 정보 응답({@code GET /a/api/{version}/users/me}).
 *
 * <p>{@code nickname}은 nullable이다 — 값이 없으면 key를 생략하지 않고 명시적 JSON {@code null}로
 * 내보낸다(클라이언트가 "닉네임 없음"을 구분하도록 NON_NULL 직렬화 설정을 붙이지 않는다).
 */
public record UserProfileResponse(String nickname) {
}
