package com.laimory.server.auth.service;

import com.laimory.server.user.Provider;
import com.laimory.server.user.User;
import com.laimory.server.user.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 소셜 로그인 완료 오케스트레이터. Repository를 직접 주입하지 않고
 * {@link UserService}·{@link AppCodeService}를 합성한다(1:1 규칙).
 *
 * <p><b>트랜잭션 부여 금지</b> — {@code UserService.findOrCreate}의 동시 최초 로그인 수렴(catch-재조회)이
 * 둘러싼 트랜잭션 안에서는 rollback-only 오염으로 깨진다(UserService 주석 참고). DB 쓰기는 그 한 건뿐이고
 * 나머지는 Redis라 묶을 트랜잭션도 없다.
 */
@Service
@RequiredArgsConstructor
public class SocialLoginService {

    private final UserService userService;
    private final AppCodeService appCodeService;

    /**
     * 검증 완료된 OIDC 사용자로 로그인을 마무리한다: find-or-create 후 challenge를 바인딩한
     * 일회용 app_code를 발급해 원문을 반환한다(앱이 {@code POST /auth/token}으로 교환).
     */
    public String completeLogin(Provider provider, String providerUserId, String email, String nickname,
                                String appChallenge) {
        User user = userService.findOrCreate(provider, providerUserId, email, nickname);
        return appCodeService.issue(user.getUserId(), appChallenge);
    }
}
