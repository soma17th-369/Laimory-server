package com.laimory.server.user.service;
import com.laimory.server.user.UserStatus;


/**
 * 인증·token 발급 경계가 회원의 활성 여부를 확인하는 최소 계약(#305).
 *
 * <p>{@code JwtAuthenticationFilter}의 {@code /a/api} 요청 검사와 token/refresh 발급 경로가
 * 사용한다. 회원 없음과 {@code WITHDRAWAL_PENDING}은 구분하지 않고 둘 다 비활성이다 — 호출자는
 * 각자의 기존 credential 오류(401 {@code -2001}/{@code -2002}/{@code -2003})로 수렴시키고 탈퇴
 * 상태를 노출하지 않는다. 구현은 둘이다(#429): 필터 경로는 캐시 구현({@code RedisActiveStatusCache},
 * ACTIVE=true만·탈퇴 evict·TTL 안전망 — 한시적 stale은 #429 "보안 정책 개정"이 허용), 발급·회전
 * 경로는 DB 직행 구현({@code UserAccountService})만 쓴다(회전 사슬 1회 종결 보장).
 */
public interface UserAccountAccessService {

    /** userId의 회원 행이 존재하고 {@link UserStatus#ACTIVE}인지 확인한다. DB 장애는 예외로 전파한다. */
    boolean isActive(long userId);
}
