package com.laimory.server.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.laimory.server.user.Provider;
import com.laimory.server.user.entity.User;
import com.laimory.server.user.service.UserService;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 소셜 로그인 완료 오케스트레이션: find-or-create된 userId로 challenge 바인딩 app_code를 발급한다. 인프라 0. */
@ExtendWith(MockitoExtension.class)
class SocialLoginServiceTest {

    @Mock
    private UserService userService;

    @Mock
    private AppCodeService appCodeService;

    @Test
    void completeLogin_findsOrCreatesUser_thenIssuesAppCodeBoundToChallenge() throws Exception {
        User user = userWithId(42L);
        when(userService.findOrCreate(Provider.GOOGLE, "sub-1", "e@x.com", "이름")).thenReturn(user);
        when(appCodeService.issue(42L, "challenge-43")).thenReturn("raw-app-code");

        String appCode = new SocialLoginService(userService, appCodeService)
                .completeLogin(Provider.GOOGLE, "sub-1", "e@x.com", "이름", "challenge-43");

        assertThat(appCode).isEqualTo("raw-app-code");
    }

    /** userId는 @GeneratedValue라 팩토리로 못 채운다 — 테스트에서만 리플렉션으로 주입. */
    private static User userWithId(long userId) throws Exception {
        User user = User.of(Provider.GOOGLE, "sub-1", "e@x.com", "이름");
        Field field = User.class.getDeclaredField("userId");
        field.setAccessible(true);
        field.set(user, userId);
        return user;
    }
}
