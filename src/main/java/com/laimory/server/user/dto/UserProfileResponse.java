package com.laimory.server.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 내 회원 정보 응답({@code GET /a/api/{version}/users/me}).
 *
 * <p>{@code nickname}은 nullable이다 — 값이 없으면 key를 생략하지 않고 명시적 JSON {@code null}로
 * 내보낸다(클라이언트가 "닉네임 없음"을 구분하도록 NON_NULL 직렬화 설정을 붙이지 않는다).
 * OpenAPI에도 같은 계약을 required + nullable로 노출한다.
 */
public record UserProfileResponse(
        @Schema(description = "닉네임 — 값이 없으면 key 생략 없이 명시적 JSON null",
                nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
        String nickname) {
}
