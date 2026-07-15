package com.laimory.server.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * API 필터체인(OAuth 핸드셰이크 경로 외 전부) — stateless + <b>전 경로 permitAll</b>.
 *
 * <p>⚠️ {@code /a/api}(인증 prefix)도 아직 permitAll이다: 클라이언트 로그인 구현 전까지 타임라인 API가
 * 무인증으로 열려 있어야 해서(고정 userId=0 동작 유지), 인증 강제·JWT 필터·userId 전파는 통째로
 * #108(Backlog)로 지연했다. 이 체인의 역할은 starter-oauth2-client가 켜는 Boot 기본 보안(전 경로 401)을
 * 대체해 기존 동작을 보존하는 것 — #108에서 이 체인에 게이트만 켠다.
 *
 * <p>OAuth 핸드셰이크 체인(@Order(100), 세션 사용)은 {@link OAuth2LoginSecurityConfig}에 분리 —
 * 슬라이스 테스트가 이 설정만 {@code @Import} 해도 ClientRegistration 의존 없이 뜨게 하기 위함.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    @Order(200)
    public SecurityFilterChain apiFilterChain(HttpSecurity http) throws Exception {
        http
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable) // 기본 /logout(세션 로그아웃) 제거 — 우리 로그아웃은 /api/{v}/auth/logout
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll());
        return http.build();
    }
}
