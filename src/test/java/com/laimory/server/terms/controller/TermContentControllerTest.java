package com.laimory.server.terms.controller;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class TermContentControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new TermContentController()).build();
    }

    @ParameterizedTest
    @MethodSource("publishedDocuments")
    void publishedVersion_returnsImmutablePublicHtml(String slug, String title) throws Exception {
        mockMvc.perform(get("/terms/{slug}/1.0", slug))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/html;charset=UTF-8"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL,
                        containsString("max-age=31536000")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("public")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("immutable")))
                .andExpect(header().exists(HttpHeaders.CONTENT_LENGTH))
                .andExpect(content().string(containsString(title)));
    }

    @Test
    void head_returnsSamePublishedMetadataWithoutBody() throws Exception {
        mockMvc.perform(head("/terms/privacy-policy/1.0"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/html;charset=UTF-8"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("immutable")));
    }

    @Test
    void unknownOrUnsafeDocument_returns404() throws Exception {
        mockMvc.perform(get("/terms/not-published/1.0"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/terms/privacy-policy/not-a-version"))
                .andExpect(status().isNotFound());
    }

    private static Stream<Arguments> publishedDocuments() {
        return Stream.of(
                Arguments.of("terms-of-service", "라이모리 이용약관"),
                Arguments.of("third-party-provision-consent", "개인정보 제3자 제공 동의"),
                Arguments.of("sensitive-information-consent", "민감정보 처리 동의"),
                Arguments.of("cross-border-transfer-consent", "개인정보 국외 이전 동의"),
                Arguments.of("location-based-service-terms", "라이모리 위치기반서비스 이용약관"),
                Arguments.of("privacy-policy", "라이모리 개인정보 처리방침")
        );
    }
}
