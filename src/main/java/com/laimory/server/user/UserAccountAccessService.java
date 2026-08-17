package com.laimory.server.user;

/**
 * 인증·token 발급 경계가 회원의 활성 여부를 확인하는 최소 계약(#305).
 *
 * <p>{@code JwtAuthenticationFilter}의 매 {@code /a/api} 요청 검사와 token/refresh 발급 경로가
 * 사용한다. 회원 없음과 {@code WITHDRAWAL_PENDING}은 구분하지 않고 둘 다 비활성이다 — 호출자는
 * 각자의 기존 credential 오류(401 {@code -2001}/{@code -2002}/{@code -2003})로 수렴시키고 탈퇴
 * 상태를 노출하지 않는다. 결과를 cache하지 않는다(탈퇴 직후 stale 허용 창 금지).
 */
public interface UserAccountAccessService {

    /** userId의 회원 행이 존재하고 {@link UserStatus#ACTIVE}인지 확인한다. DB 장애는 예외로 전파한다. */
    boolean isActive(long userId);
}
