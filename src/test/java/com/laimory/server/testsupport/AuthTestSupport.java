package com.laimory.server.testsupport;

import com.laimory.server.auth.token.JwtTokens;
import com.laimory.server.user.UserAccountAccessService;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * 슬라이스 테스트의 인증 지원 유틸.
 *
 * <p>{@link #authenticatedUser(long)}는 실제 {@code JwtAuthenticationFilter}가 만드는 것과 동일한
 * <b>Long principal</b> 인증을 주입한다 — {@code SecurityMockMvcRequestPostProcessors.user(...)}는
 * String principal을 만들어 {@code @AuthenticationPrincipal(errorOnInvalidType = true) Long}과 타입이
 * 어긋나므로 사용하지 않는다.
 *
 * <p>{@link JwtTokensTestConfig}는 {@code SecurityConfig}를 {@code @Import}하는 {@code @WebMvcTest}
 * 슬라이스에 JWT 필터 빈 생성에 필요한 {@link JwtTokens}를 제공한다(슬라이스는 @Component 스캔을 안 하므로).
 */
public final class AuthTestSupport {

    /** 32바이트 이상(HS256 최소 키 길이) 테스트 전용 시크릿. */
    public static final String TEST_JWT_SECRET = "test-jwt-secret-0123456789abcdef";

    private AuthTestSupport() {
    }

    /** 실제 JWT 필터와 동일한 형태(Long principal, credentials 없음, 권한 없음)의 인증을 주입한다. */
    public static RequestPostProcessor authenticatedUser(long userId) {
        return SecurityMockMvcRequestPostProcessors.authentication(
                UsernamePasswordAuthenticationToken.authenticated(userId, null, List.of()));
    }

    /** {@code @Import({SecurityConfig.class, AuthTestSupport.JwtTokensTestConfig.class})}로 사용한다. */
    @TestConfiguration
    public static class JwtTokensTestConfig {
        @Bean
        JwtTokens jwtTokens() {
            return new JwtTokens(TEST_JWT_SECRET, Duration.ofMinutes(15), Clock.systemUTC());
        }

        /**
         * JWT 필터의 매 요청 active 검사(#305)용 슬라이스 기본 빈 — 항상 활성 회원으로 취급한다.
         * 탈퇴·장애 시나리오가 필요한 테스트는 {@code @MockitoBean UserAccountAccessService}로 대체한다.
         */
        @Bean
        UserAccountAccessService userAccountAccessService() {
            return userId -> true;
        }
    }
}
