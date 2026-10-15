package com.laimory.server.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationExchange;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationResponse;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;

/**
 * 로그인 핸드셰이크 외부 호출이 응답 없는 인증 서버에서 무한 대기하지 않고 read timeout으로 실패하는지(#583) —
 * production 클라이언트 구성을 loopback {@link MockWebServer}로 검증한다. 테스트는 짧은 read timeout을 넘긴다.
 */
class OAuth2HandshakeTimeoutTest {

    private static final Duration READ_TIMEOUT = Duration.ofMillis(300);
    private static final Duration UPPER_BOUND = Duration.ofSeconds(5);

    private MockWebServer server;

    @BeforeEach
    void startServer() throws Exception {
        server = new MockWebServer();
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        server.start();
    }

    @AfterEach
    void stopServer() throws Exception {
        server.shutdown();
    }

    @Test
    void tokenExchangeFailsWithReadTimeoutWhenProviderHangs() {
        var client = OAuth2LoginSecurityConfig.authorizationCodeTokenResponseClient(READ_TIMEOUT);
        OAuth2AuthorizationCodeGrantRequest request = authorizationCodeGrantRequest(registration());

        assertTimeoutPreemptively(UPPER_BOUND, () ->
                assertThatThrownBy(() -> client.getTokenResponse(request))
                        .isInstanceOf(OAuth2AuthorizationException.class)
                        .hasRootCauseInstanceOf(SocketTimeoutException.class));
    }

    @Test
    void userInfoFailsWithReadTimeoutWhenProviderHangs() {
        var service = OAuth2LoginSecurityConfig.oidcUserService(READ_TIMEOUT);
        OidcUserRequest request = oidcUserRequest(registration());

        assertTimeoutPreemptively(UPPER_BOUND, () ->
                assertThatThrownBy(() -> service.loadUser(request))
                        .isInstanceOf(OAuth2AuthenticationException.class)
                        .hasRootCauseInstanceOf(SocketTimeoutException.class));
    }

    private ClientRegistration registration() {
        return ClientRegistration.withRegistrationId("google")
                .clientId("client-id")
                .clientSecret("client-secret")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://example.test/login/oauth2/code/google")
                .scope("openid", "profile")
                .authorizationUri(server.url("/authorize").toString())
                .tokenUri(server.url("/token").toString())
                .userInfoUri(server.url("/userinfo").toString())
                .userNameAttributeName("sub")
                .jwkSetUri(server.url("/jwks").toString())
                .build();
    }

    private static OAuth2AuthorizationCodeGrantRequest authorizationCodeGrantRequest(ClientRegistration registration) {
        OAuth2AuthorizationRequest authorizationRequest = OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri(registration.getProviderDetails().getAuthorizationUri())
                .clientId(registration.getClientId())
                .redirectUri(registration.getRedirectUri())
                .state("state")
                .build();
        OAuth2AuthorizationResponse authorizationResponse = OAuth2AuthorizationResponse.success("code")
                .redirectUri(registration.getRedirectUri())
                .state("state")
                .build();
        return new OAuth2AuthorizationCodeGrantRequest(registration,
                new OAuth2AuthorizationExchange(authorizationRequest, authorizationResponse));
    }

    private static OidcUserRequest oidcUserRequest(ClientRegistration registration) {
        Instant issuedAt = Instant.now();
        OAuth2AccessToken accessToken = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "access-token",
                issuedAt, issuedAt.plusSeconds(60), Set.of("openid", "profile"));
        OidcIdToken idToken = new OidcIdToken("id-token", issuedAt, issuedAt.plusSeconds(60),
                Map.of("sub", "subject", "iss", "https://example.test"));
        return new OidcUserRequest(registration, accessToken, idToken);
    }
}
