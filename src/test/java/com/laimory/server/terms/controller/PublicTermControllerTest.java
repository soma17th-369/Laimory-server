package com.laimory.server.terms.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.laimory.server.config.SecurityConfig;
import com.laimory.server.terms.TermType;
import com.laimory.server.terms.entity.TermDocument;
import com.laimory.server.terms.service.TermDocumentService;
import com.laimory.server.testsupport.AuthTestSupport;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 공개 약관 조회 컨트롤러 슬라이스 테스트(MockMvc). 무인증 200(public 계약)·termTypes 필수/빈 값/미지원 400·
 * 고정 화면 순서·offset 없는 KST LocalDateTime 직렬화·빈 catalog 200/[]와, 원문 대신 버전별
 * contentUrl만 나가는 wire 계약을 검증한다. 인프라 0.
 */
@WebMvcTest(PublicTermController.class)
@Import({SecurityConfig.class, AuthTestSupport.JwtTokensTestConfig.class})
class PublicTermControllerTest {

    private static final String PATH = "/api/v1/terms";
    private static final String VERSION = "1.0";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TermDocumentService termDocumentService;

    @Test
    void getCurrentTerms_withoutBearer_returns200InFixedDisplayOrder() throws Exception {
        // 로그인 전 화면에서 쓰는 public API — bearer 없이 200이어야 한다.
        when(termDocumentService.findCurrentDocuments("v1", List.of(
                TermType.LOCATION_BASED_SERVICE_TERMS,
                TermType.TERMS_OF_SERVICE)))
                .thenReturn(List.of(
                        document(TermType.TERMS_OF_SERVICE, "이용약관"),
                        document(TermType.LOCATION_BASED_SERVICE_TERMS, "위치기반서비스 이용약관")));

        mockMvc.perform(get(PATH).param("termTypes",
                        "LOCATION_BASED_SERVICE_TERMS", "TERMS_OF_SERVICE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.code").value(0))
                .andExpect(header().exists("Transaction-Id"))
                .andExpect(jsonPath("$.body.terms[0].termType").value("TERMS_OF_SERVICE"))
                .andExpect(jsonPath("$.body.terms[0].title").value("이용약관"))
                .andExpect(jsonPath("$.body.terms[0].version").value("1.0"))
                // 원문은 응답에서 사라지고 버전별 page URL만 남는다(#320).
                .andExpect(jsonPath("$.body.terms[0].content").doesNotExist())
                .andExpect(jsonPath("$.body.terms[0].contentUrl")
                        .value("https://laimory.app/terms/terms-of-service/1.0"))
                .andExpect(jsonPath("$.body.terms[0].required").value(true))
                // KST 벽시계 LocalDateTime — offset 없는 ISO 문자열로 직렬화된다.
                .andExpect(jsonPath("$.body.terms[0].effectiveAt").value("2026-08-01T09:30:15"))
                .andExpect(jsonPath("$.body.terms[1].termType").value("LOCATION_BASED_SERVICE_TERMS"))
                .andExpect(jsonPath("$.body.terms[1].required").value(false))
                .andExpect(jsonPath("$.body.terms").isArray())
                .andExpect(jsonPath("$.body.terms.length()").value(2));

        verify(termDocumentService).findCurrentDocuments("v1", List.of(
                TermType.LOCATION_BASED_SERVICE_TERMS,
                TermType.TERMS_OF_SERVICE));
    }

    @Test
    void getCurrentTerms_emptyCatalog_returns200WithEmptyArray() throws Exception {
        // 활성화 전 rollout 상태 — 404/500이 아니라 200 + 빈 배열이다.
        when(termDocumentService.findCurrentDocuments("v1", List.of(TermType.CROSS_BORDER_TRANSFER_CONSENT)))
                .thenReturn(List.of());

        mockMvc.perform(get(PATH).param("termTypes", "CROSS_BORDER_TRANSFER_CONSENT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.code").value(0))
                .andExpect(jsonPath("$.body.terms").isArray())
                .andExpect(jsonPath("$.body.terms").isEmpty());
    }

    @Test
    void getTimelineTerms_returnsLocationLastAndConditional() throws Exception {
        when(termDocumentService.findCurrentDocuments("v1", List.of(
                TermType.LOCATION_BASED_SERVICE_TERMS,
                TermType.CROSS_BORDER_TRANSFER_CONSENT,
                TermType.THIRD_PARTY_PROVISION_CONSENT,
                TermType.SENSITIVE_INFORMATION_CONSENT)))
                .thenReturn(List.of(
                        document(TermType.SENSITIVE_INFORMATION_CONSENT, "민감정보 처리 동의"),
                        document(TermType.THIRD_PARTY_PROVISION_CONSENT, "개인정보 제3자 제공 동의"),
                        document(TermType.CROSS_BORDER_TRANSFER_CONSENT, "개인정보 국외 이전 동의"),
                        document(TermType.LOCATION_BASED_SERVICE_TERMS, "위치기반서비스 이용약관")));

        mockMvc.perform(get(PATH).param("termTypes",
                        "LOCATION_BASED_SERVICE_TERMS",
                        "CROSS_BORDER_TRANSFER_CONSENT",
                        "THIRD_PARTY_PROVISION_CONSENT",
                        "SENSITIVE_INFORMATION_CONSENT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.terms.length()").value(4))
                .andExpect(jsonPath("$.body.terms[0].termType").value("SENSITIVE_INFORMATION_CONSENT"))
                .andExpect(jsonPath("$.body.terms[1].termType").value("THIRD_PARTY_PROVISION_CONSENT"))
                .andExpect(jsonPath("$.body.terms[2].termType").value("CROSS_BORDER_TRANSFER_CONSENT"))
                .andExpect(jsonPath("$.body.terms[3].termType").value("LOCATION_BASED_SERVICE_TERMS"))
                .andExpect(jsonPath("$.body.terms[3].required").value(false));
    }

    @Test
    void getCurrentTerms_missingTermTypes_returns400() throws Exception {
        mockMvc.perform(get(PATH))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.code").value(-400))
                .andExpect(jsonPath("$.body").doesNotExist());

        verifyNoInteractions(termDocumentService);
    }

    @Test
    void getCurrentTerms_emptyTermTypes_returns400() throws Exception {
        mockMvc.perform(get(PATH).param("termTypes", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.code").value(-400));

        verifyNoInteractions(termDocumentService);
    }

    @Test
    void getCurrentTerms_unsupportedTermType_returns400() throws Exception {
        mockMvc.perform(get(PATH).param("termTypes", "MARKETING_CONSENT"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.code").value(-400));

        verifyNoInteractions(termDocumentService);
    }

    @Test
    void getCurrentTerms_legacyStageOnly_returns400() throws Exception {
        mockMvc.perform(get(PATH).param("stage", "LOGIN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.code").value(-400));

        verifyNoInteractions(termDocumentService);
    }

    private static TermDocument document(TermType type, String title) {
        return TermDocument.of(type, VERSION, title, url(type), LocalDateTime.parse("2026-08-01T09:30:15"));
    }

    /** 게시 URL은 행에 저장된 값이라 fixture가 그대로 정한다(서버가 규칙으로 만들지 않는다). */
    private static String url(TermType type) {
        return "https://laimory.app/terms/" + type.name().toLowerCase().replace('_', '-') + "/" + VERSION;
    }
}
