package com.laimory.server.auth;

/**
 * refresh token 상태. ACTIVE → ROTATED(정상 회전) / REVOKED(로그아웃·재사용 탐지 폐기).
 * ROTATED/REVOKED 토큰이 재제시되면 재사용 탐지로 해당 사용자 refresh 전체를 REVOKED 처리한다.
 */
public enum RefreshTokenStatus {
    ACTIVE,
    ROTATED,
    REVOKED
}
