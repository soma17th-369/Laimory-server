package com.laimory.server.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.auth.security.AppChallengeFilter;
import com.laimory.server.auth.security.OAuth2LoginFailureHandler;
import com.laimory.server.auth.security.OAuth2LoginSuccessHandler;
import com.laimory.server.auth.service.SocialLoginService;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.savedrequest.NullRequestCache;

/**
 * OAuth 핸드셰이크 체인({@code /oauth2/**}, {@code /login/**}) — Google·Kakao OIDC 로그인 전용.
 *
 * <p>state/nonce/PKCE 생성·검증, code↔token 교환, id_token 검증(서명/aud/exp/nonce)은 전부
 * Spring Security({@code oauth2Login})가 수행한다. 우리 몫은 앞뒤 두 조각뿐:
 * 시작 시 {@link AppChallengeFilter}(핸드오프 PKCE challenge 세션 보관), 끝에서
 * {@link OAuth2LoginSuccessHandler}(app_code 발급 + 핸드오프 302).
 *
 * <p>이 체인만 세션을 쓴다(인가요청·nonce·challenge 보관). 세션은 Spring Session(Redis)이라
 * 이중화 시 콜백이 다른 인스턴스에 떨어져도 공유되고, 로그인 완료 시 invalidate된다.
 */
@Configuration
public class OAuth2LoginSecurityConfig {

    @Bean
    @Order(1)
    public SecurityFilterChain oauth2LoginFilterChain(HttpSecurity http,
                                                      ClientRegistrationRepository clientRegistrationRepository,
                                                      AppChallengeFilter appChallengeFilter,
                                                      OAuth2LoginSuccessHandler successHandler,
                                                      OAuth2LoginFailureHandler failureHandler) throws Exception {
        http
                .securityMatcher("/oauth2/**", "/login/**")
                // 이 체인의 요청은 전부 GET 302 흐름 — CSRF 폼 대상이 없고, 콜백 위조는 state가 방어한다.
                .csrf(AbstractHttpConfigurer::disable)
                // 딥링크 핸드오프 흐름이라 "원래 가려던 페이지" 복원을 안 쓴다 — 세션에 저장 요청을 남기지 않는다.
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                .addFilterBefore(appChallengeFilter, OAuth2AuthorizationRequestRedirectFilter.class)
                .oauth2Login(login -> login
                        .authorizationEndpoint(endpoint -> endpoint.authorizationRequestResolver(
                                pkceAuthorizationRequestResolver(clientRegistrationRepository)))
                        .successHandler(successHandler)
                        .failureHandler(failureHandler));
        return http.build();
    }

    /** confidential 클라이언트에도 PKCE(S256)를 강제한다(OAuth 2.1 — 기본은 public 클라이언트만 적용). */
    private static OAuth2AuthorizationRequestResolver pkceAuthorizationRequestResolver(
            ClientRegistrationRepository clientRegistrationRepository) {
        DefaultOAuth2AuthorizationRequestResolver resolver = new DefaultOAuth2AuthorizationRequestResolver(
                clientRegistrationRepository,
                OAuth2AuthorizationRequestRedirectFilter.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI);
        resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
        return resolver;
    }

    @Bean
    public AppChallengeFilter appChallengeFilter(MessageSource messageSource, ObjectMapper objectMapper) {
        return new AppChallengeFilter(messageSource, objectMapper);
    }

    /** Filter 빈은 Boot가 서블릿 필터로도 자동 등록한다 — Security 체인 안에서만 실행되도록 전역 등록을 끈다. */
    @Bean
    public FilterRegistrationBean<AppChallengeFilter> appChallengeFilterRegistration(AppChallengeFilter filter) {
        FilterRegistrationBean<AppChallengeFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public OAuth2LoginSuccessHandler oauth2LoginSuccessHandler(SocialLoginService socialLoginService) {
        return new OAuth2LoginSuccessHandler(socialLoginService);
    }

    @Bean
    public OAuth2LoginFailureHandler oauth2LoginFailureHandler() {
        return new OAuth2LoginFailureHandler();
    }
}
