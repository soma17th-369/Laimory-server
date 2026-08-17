package com.laimory.server.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.auth.security.ApiAuthenticationEntryPoint;
import com.laimory.server.auth.security.ApiErrorResponseWriter;
import com.laimory.server.auth.security.JwtAuthenticationFilter;
import com.laimory.server.auth.token.JwtTokens;
import com.laimory.server.common.ApiUrls;
import com.laimory.server.user.UserAccountAccessService;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.savedrequest.NullRequestCache;

/**
 * API 필터체인(OAuth 핸드셰이크 경로 외 전부) — stateless.
 *
 * <p>{@code /a/api}(정확한 prefix와 하위 경로만)는 {@link JwtAuthenticationFilter}가 만든 인증이 있어야
 * 접근할 수 있고, 무인증 거절은 {@link ApiAuthenticationEntryPoint}가 401 {@code -2001} envelope로
 * 직접 응답한다. 나머지 경로는 permitAll 유지 — denyAll로 잠그면 미매핑 경로의 404 계약이 401로
 * 회귀하므로 공개 경로({@code /api}, {@code /s/api}, {@code /status} 등)와 미매핑 경로를 함께 열어 둔다.
 *
 * <p>OAuth 핸드셰이크 체인(@Order(100), 세션 사용)은 {@link OAuth2LoginSecurityConfig}에 분리 —
 * 슬라이스 테스트가 이 설정만 {@code @Import} 해도 ClientRegistration 의존 없이 뜨게 하기 위함.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    @Order(200)
    public SecurityFilterChain apiFilterChain(HttpSecurity http,
                                              JwtAuthenticationFilter jwtAuthenticationFilter,
                                              ApiAuthenticationEntryPoint apiAuthenticationEntryPoint)
            throws Exception {
        http
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable) // 기본 /logout(세션 로그아웃) 제거 — 우리 로그아웃은 /api/{v}/auth/logout
                // stateless 체인이라 "원래 가려던 요청" 복원이 없다 — 세션 저장 요청을 남기지 않는다.
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                .addFilterBefore(jwtAuthenticationFilter, AuthorizationFilter.class)
                .exceptionHandling(handling -> handling.authenticationEntryPoint(apiAuthenticationEntryPoint))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(ApiUrls.AUTHENTICATED_API_PREFIX,
                                ApiUrls.AUTHENTICATED_API_PREFIX + "/**").authenticated()
                        .anyRequest().permitAll());
        return http.build();
    }

    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter(JwtTokens jwtTokens,
                                                           UserAccountAccessService userAccountAccessService,
                                                           ApiErrorResponseWriter apiErrorResponseWriter) {
        // #305: JWT 파싱 후 매 요청 active 검사 — 탈퇴 회원의 기존 access token을 즉시 차단한다.
        return new JwtAuthenticationFilter(jwtTokens, userAccountAccessService, apiErrorResponseWriter);
    }

    /** Filter 빈은 Boot가 서블릿 필터로도 자동 등록한다 — Security 체인 안에서만 실행되도록 전역 등록을 끈다. */
    @Bean
    public FilterRegistrationBean<JwtAuthenticationFilter> jwtAuthenticationFilterRegistration(
            JwtAuthenticationFilter filter) {
        FilterRegistrationBean<JwtAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    /** filter 단계 error envelope 공용 작성기 — 401 EntryPoint와 JWT 필터의 fail-closed 500이 공유한다. */
    @Bean
    public ApiErrorResponseWriter apiErrorResponseWriter(MessageSource messageSource, ObjectMapper objectMapper) {
        return new ApiErrorResponseWriter(messageSource, objectMapper);
    }

    @Bean
    public ApiAuthenticationEntryPoint apiAuthenticationEntryPoint(ApiErrorResponseWriter apiErrorResponseWriter) {
        return new ApiAuthenticationEntryPoint(apiErrorResponseWriter);
    }
}
