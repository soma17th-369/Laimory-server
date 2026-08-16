package com.laimory.server.terms.controller;

import static com.laimory.server.testsupport.AuthTestSupport.authenticatedUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.config.SecurityConfig;
import com.laimory.server.terms.TermType;
import com.laimory.server.terms.entity.TermAgreement;
import com.laimory.server.terms.entity.TermDocument;
import com.laimory.server.terms.service.TermAgreementCommand;
import com.laimory.server.terms.service.TermAgreementHistoryEntry;
import com.laimory.server.terms.service.TermAgreementService;
import com.laimory.server.testsupport.AuthTestSupport;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 약관 동의 컨트롤러 슬라이스 테스트(MockMvc). 인증 게이트(401)·envelope·상태 매핑(200/400/409)과
 * "userId는 인증 principal에서 서비스로 전달", 이력 응답의 불변 내용 재구성·빈 이력 200/[]를 검증한다.
 * 인프라 0. (hidden principal·bearerAuth 문서 계약은 {@code arch.ApiAuthenticationContractTest} 소유.)
 */
@WebMvcTest(TermAgreementController.class)
@Import({SecurityConfig.class, AuthTestSupport.JwtTokensTestConfig.class})
class TermAgreementControllerTest {

    private static final long USER_ID = 7L;
    private static final String PATH = "/a/api/v1/terms/agreements";
    private static final String BODY = """
            {"agreements": [
              {"termType": "TERMS_OF_SERVICE", "version": "2026-08-15"},
              {"termType": "PRIVACY_POLICY", "version": "2026-08-15"}
            ]}
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private TermAgreementService termAgreementService;

    @Test
    void unauthenticatedRequests_rejected401BeforeService() throws Exception {
        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.header.code").value(-2001));
        mockMvc.perform(get(PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.header.code").value(-2001));

        verifyNoInteractions(termAgreementService);
    }

    @Test
    void agree_returns200WithNullBody_andPassesPrincipalAndParsedCommands() throws Exception {
        MvcResult result = mockMvc.perform(post(PATH).with(authenticatedUser(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.code").value(0))
                .andExpect(header().exists("Transaction-Id"))
                .andReturn();

        // 성공 body는 명시적 JSON null이다(key 생략 아님).
        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        assertThat(response.has("body")).isTrue();
        assertThat(response.get("body").isNull()).isTrue();

        verify(termAgreementService).agreeToTerms("v1", USER_ID, List.of(
                new TermAgreementCommand(TermType.TERMS_OF_SERVICE, "2026-08-15"),
                new TermAgreementCommand(TermType.PRIVACY_POLICY, "2026-08-15")));
    }

    @Test
    void agree_staleVersion_returns409WithCode3002() throws Exception {
        doThrow(new BusinessException(ExceptionType.STALE_TERM_VERSION))
                .when(termAgreementService).agreeToTerms(anyString(), anyLong(), anyList());

        mockMvc.perform(post(PATH).with(authenticatedUser(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.header.code").value(-3002))
                .andExpect(jsonPath("$.header.message").isNotEmpty())
                .andExpect(jsonPath("$.body").doesNotExist());
    }

    @Test
    void agree_invalidShape_mapsIllegalArgumentTo400() throws Exception {
        doThrow(new IllegalArgumentException("agreements must not be null or empty"))
                .when(termAgreementService).agreeToTerms(anyString(), anyLong(), any());

        mockMvc.perform(post(PATH).with(authenticatedUser(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"agreements\": []}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.code").value(-400))
                .andExpect(jsonPath("$.body").doesNotExist());
    }

    @Test
    void agree_unknownTermTypeLiteral_returns400BeforeService() throws Exception {
        // 미지원 enum literal은 역직렬화 단계에서 거절된다(전방 미호환 입력을 조용히 무시하지 않음).
        mockMvc.perform(post(PATH).with(authenticatedUser(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agreements\": [{\"termType\": \"BOGUS\", \"version\": \"1\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.code").value(-400));

        verifyNoInteractions(termAgreementService);
    }

    @Test
    void history_returns200WithImmutableContents_inServiceOrder() throws Exception {
        when(termAgreementService.getHistory("v1", USER_ID)).thenReturn(List.of(
                entry(TermType.PRIVACY_POLICY, "2026-08-15", "개인정보 처리방침", "2026-08-16T09:30:05"),
                entry(TermType.TERMS_OF_SERVICE, "2026-07-01", "이용약관", "2026-07-02T10:00:05")));

        mockMvc.perform(get(PATH).with(authenticatedUser(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.code").value(0))
                .andExpect(jsonPath("$.body.agreements[0].termType").value("PRIVACY_POLICY"))
                .andExpect(jsonPath("$.body.agreements[0].version").value("2026-08-15"))
                .andExpect(jsonPath("$.body.agreements[0].title").value("개인정보 처리방침"))
                .andExpect(jsonPath("$.body.agreements[0].content").value("fixture-content"))
                .andExpect(jsonPath("$.body.agreements[0].required").value(true))
                .andExpect(jsonPath("$.body.agreements[0].effectiveAt").value("2026-08-01T09:30:15"))
                // 수락 시각도 offset 없는 KST 벽시계 문자열이다.
                .andExpect(jsonPath("$.body.agreements[0].acceptedAt").value("2026-08-16T09:30:05"))
                .andExpect(jsonPath("$.body.agreements[1].termType").value("TERMS_OF_SERVICE"))
                .andExpect(jsonPath("$.body.agreements[1].acceptedAt").value("2026-07-02T10:00:05"));

        verify(termAgreementService).getHistory("v1", USER_ID);
    }

    @Test
    void history_empty_returns200WithEmptyArray() throws Exception {
        when(termAgreementService.getHistory("v1", USER_ID)).thenReturn(List.of());

        mockMvc.perform(get(PATH).with(authenticatedUser(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.code").value(0))
                .andExpect(jsonPath("$.body.agreements").isArray())
                .andExpect(jsonPath("$.body.agreements").isEmpty());
    }

    private static TermAgreementHistoryEntry entry(TermType type, String version, String title,
                                                   String acceptedAt) {
        TermDocument document = TermDocument.of(type, version, title, "fixture-content",
                LocalDateTime.parse("2026-08-01T09:30:15"));
        TermAgreement agreement = BeanUtils.instantiateClass(TermAgreement.class);
        ReflectionTestUtils.setField(agreement, "userId", USER_ID);
        ReflectionTestUtils.setField(agreement, "acceptedAt", LocalDateTime.parse(acceptedAt));
        return new TermAgreementHistoryEntry(agreement, document);
    }
}
