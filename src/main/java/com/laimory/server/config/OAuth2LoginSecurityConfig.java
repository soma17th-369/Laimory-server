package com.laimory.server.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.auth.security.AppChallengeFilter;
import com.laimory.server.auth.security.OAuth2LoginFailureHandler;
import com.laimory.server.auth.security.OAuth2LoginSuccessHandler;
import com.laimory.server.auth.service.SocialLoginService;
import java.time.Duration;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

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
 *
 * <p>핸드셰이크의 외부 호출(code↔token 교환, OIDC userinfo)에는 유한 timeout을 건다(#583). Spring Security 기본
 * 클라이언트는 자체 RestTemplate/RestClient를 만들어 connect·read timeout이 무한이고 {@code spring.http.client.*}도
 * 받지 않는다 — 카카오·구글 인증 서버가 응답 없이 멈추면 로그인 콜백마다 Tomcat 스레드가 무기한 묶여 같은
 * 커넥터의 API와 {@code /readyz}까지 밀린다. id_token 서명 검증용 JWK 조회는 Nimbus가 캐시하고, 같은 인증
 * 서버가 멈추면 앞선 token 교환이 먼저 timeout되므로 기본값으로 둔다.
 */
@Configuration
public class OAuth2LoginSecurityConfig {

    static final Duration HANDSHAKE_CONNECT_TIMEOUT = Duration.ofSeconds(2);
    static final Duration HANDSHAKE_READ_TIMEOUT = Duration.ofSeconds(5);

    @Bean
    @Order(100)
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
                                oauth2AuthorizationRequestResolver(clientRegistrationRepository)))
                        .tokenEndpoint(token -> token.accessTokenResponseClient(
                                authorizationCodeTokenResponseClient(HANDSHAKE_READ_TIMEOUT)))
                        .userInfoEndpoint(userInfo -> userInfo.oidcUserService(
                                oidcUserService(HANDSHAKE_READ_TIMEOUT)))
                        .successHandler(successHandler)
                        .failureHandler(failureHandler));
        return http.build();
    }

    /** confidential 클라이언트에도 PKCE(S256)를 강제하고, Google 로그인에는 계정 선택을 요청한다. */
    private static OAuth2AuthorizationRequestResolver oauth2AuthorizationRequestResolver(
            ClientRegistrationRepository clientRegistrationRepository) {
        DefaultOAuth2AuthorizationRequestResolver resolver = new DefaultOAuth2AuthorizationRequestResolver(
                clientRegistrationRepository,
                OAuth2AuthorizationRequestRedirectFilter.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI);
        resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce()
                .andThen(builder -> builder.attributes(attributes -> {
                    if ("google".equals(attributes.get(OAuth2ParameterNames.REGISTRATION_ID))) {
                        builder.additionalParameters(params -> params.put("prompt", "select_account"));
                    }
                })));
        return resolver;
    }

    /** 기본 token 교환 클라이언트와 같은 변환기·오류 처리에 timeout을 건 request factory만 더한다. */
    static RestClientAuthorizationCodeTokenResponseClient authorizationCodeTokenResponseClient(Duration readTimeout) {
        RestClientAuthorizationCodeTokenResponseClient client = new RestClientAuthorizationCodeTokenResponseClient();
        client.setRestClient(RestClient.builder()
                .messageConverters(converters -> {
                    converters.clear();
                    converters.add(new FormHttpMessageConverter());
                    converters.add(new OAuth2AccessTokenResponseHttpMessageConverter());
                })
                .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler())
                .requestFactory(handshakeRequestFactory(readTimeout))
                .build());
        return client;
    }

    /** 기본 OIDC user service에 timeout을 건 userinfo 클라이언트를 넣는다(오류 처리는 기본과 같다). */
    static OidcUserService oidcUserService(Duration readTimeout) {
        RestTemplate restTemplate = new RestTemplate(handshakeRequestFactory(readTimeout));
        restTemplate.setErrorHandler(new OAuth2ErrorResponseErrorHandler());
        DefaultOAuth2UserService userInfoService = new DefaultOAuth2UserService();
        userInfoService.setRestOperations(restTemplate);
        OidcUserService oidcUserService = new OidcUserService();
        oidcUserService.setOauth2UserService(userInfoService);
        return oidcUserService;
    }

    private static ClientHttpRequestFactory handshakeRequestFactory(Duration readTimeout) {
        return ClientHttpRequestFactoryBuilder.simple().build(ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(HANDSHAKE_CONNECT_TIMEOUT)
                .withReadTimeout(readTimeout));
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
