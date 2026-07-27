package com.laimory.server.auth.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.common.logging.RequestLogAttributes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.InsufficientAuthenticationException;

/**
 * /a/api 무인증 401 EntryPoint 단위 검증: ERROR_2001 envelope(body=null)·UTF-8 JSON·로캘 폴백·
 * WWW-Authenticate 헤더·access 로그 attribute·token 비노출. 인프라 0.
 */
class ApiAuthenticationEntryPointTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ApiAuthenticationEntryPoint entryPoint;

    @BeforeEach
    void setUp() {
        // 실제 번들(messages*.properties)을 그대로 사용해 메시지 키 누락도 함께 잡는다.
        ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
        messageSource.setBasename("messages");
        messageSource.setDefaultEncoding("UTF-8");
        entryPoint = new ApiAuthenticationEntryPoint(messageSource, objectMapper);
    }

    private MockHttpServletResponse commence(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        entryPoint.commence(request, response, new InsufficientAuthenticationException("no auth"));
        return response;
    }

    @Test
    void commence_writes401EnvelopeWithUtf8Json() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/a/api/v1/timeline/drafts/t");

        MockHttpServletResponse response = commence(request);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getCharacterEncoding()).isEqualToIgnoringCase("UTF-8");
        assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("Bearer");
        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertThat(body.path("header").path("code").asInt()).isEqualTo(-2001);
        assertThat(body.path("header").path("message").asText()).isNotBlank();
        assertThat(body.path("body").isNull()).isTrue();
    }

    @Test
    void commence_withoutAcceptLanguage_fallsBackToKorean() throws Exception {
        MockHttpServletResponse response = commence(
                new MockHttpServletRequest("GET", "/a/api/v1/timeline/drafts/t"));

        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertThat(body.path("header").path("message").asText()).isEqualTo("로그인이 필요해요. 다시 로그인해 주세요.");
    }

    @Test
    void commence_withEnglishAcceptLanguage_usesRequestLocale() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/a/api/v1/timeline/drafts/t");
        request.addHeader("Accept-Language", "en");

        MockHttpServletResponse response = commence(request);

        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertThat(body.path("header").path("message").asText()).isEqualTo("Sign-in is required. Please sign in again.");
    }

    @Test
    void commence_setsExceptionTypeAttributeForAccessLog() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/a/api/v1/timeline/drafts/t");

        commence(request);

        // access 로그의 errorCode=ERROR_2001·INFO 레벨은 이 attribute에서 파생된다(EXCEPTION_TYPE 계약).
        assertThat(request.getAttribute(RequestLogAttributes.EXCEPTION_TYPE))
                .isEqualTo(ExceptionType.API_AUTHENTICATION_REQUIRED);
    }

    @Test
    void commence_doesNotEchoAuthorizationHeaderOrTokenDetails() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/a/api/v1/timeline/drafts/t");
        request.addHeader("Authorization", "Bearer secret-token-value");

        MockHttpServletResponse response = commence(request);

        assertThat(response.getContentAsString())
                .doesNotContain("secret-token-value")
                .doesNotContain("Authorization");
    }
}
