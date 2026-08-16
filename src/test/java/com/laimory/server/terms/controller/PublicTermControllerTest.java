package com.laimory.server.terms.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.laimory.server.config.SecurityConfig;
import com.laimory.server.terms.TermStage;
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
 * 공개 약관 조회 컨트롤러 슬라이스 테스트(MockMvc). 무인증 200(public 계약)·stage 필수/미지원 400·
 * 고정 화면 순서·offset 없는 KST LocalDateTime 직렬화·빈 catalog 200/[]를 검증한다. 인프라 0.
 */
@WebMvcTest(PublicTermController.class)
@Import({SecurityConfig.class, AuthTestSupport.JwtTokensTestConfig.class})
class PublicTermControllerTest {

    private static final String PATH = "/api/v1/terms";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TermDocumentService termDocumentService;

    @Test
    void getCurrentTerms_withoutBearer_returns200InFixedDisplayOrder() throws Exception {
        // 로그인 전 화면에서 쓰는 public API — bearer 없이 200이어야 한다.
        when(termDocumentService.findCurrentDocuments("v1", TermStage.LOGIN)).thenReturn(List.of(
                document(TermType.TERMS_OF_SERVICE, "이용약관"),
                document(TermType.PRIVACY_POLICY, "개인정보 처리방침")));

        mockMvc.perform(get(PATH).param("stage", "LOGIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.code").value(0))
                .andExpect(header().exists("Transaction-Id"))
                .andExpect(jsonPath("$.body.terms[0].termType").value("TERMS_OF_SERVICE"))
                .andExpect(jsonPath("$.body.terms[0].title").value("이용약관"))
                .andExpect(jsonPath("$.body.terms[0].version").value("2026-08-15"))
                .andExpect(jsonPath("$.body.terms[0].content").value("fixture-content"))
                .andExpect(jsonPath("$.body.terms[0].required").value(true))
                // KST 벽시계 LocalDateTime — offset 없는 ISO 문자열로 직렬화된다.
                .andExpect(jsonPath("$.body.terms[0].effectiveAt").value("2026-08-01T09:30:15"))
                .andExpect(jsonPath("$.body.terms[1].termType").value("PRIVACY_POLICY"));

        verify(termDocumentService).findCurrentDocuments("v1", TermStage.LOGIN);
    }

    @Test
    void getCurrentTerms_emptyCatalog_returns200WithEmptyArray() throws Exception {
        // 활성화 전 rollout 상태 — 404/500이 아니라 200 + 빈 배열이다.
        when(termDocumentService.findCurrentDocuments("v1", TermStage.TIMELINE_FIRST_CREATE))
                .thenReturn(List.of());

        mockMvc.perform(get(PATH).param("stage", "TIMELINE_FIRST_CREATE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.code").value(0))
                .andExpect(jsonPath("$.body.terms").isArray())
                .andExpect(jsonPath("$.body.terms").isEmpty());
    }

    @Test
    void getCurrentTerms_missingStage_returns400() throws Exception {
        mockMvc.perform(get(PATH))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.code").value(-400))
                .andExpect(jsonPath("$.body").doesNotExist());

        verifyNoInteractions(termDocumentService);
    }

    @Test
    void getCurrentTerms_unsupportedStage_returns400() throws Exception {
        mockMvc.perform(get(PATH).param("stage", "SIGNUP"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.code").value(-400));

        verifyNoInteractions(termDocumentService);
    }

    private static TermDocument document(TermType type, String title) {
        return TermDocument.of(type, "2026-08-15", title, "fixture-content",
                LocalDateTime.parse("2026-08-01T09:30:15"));
    }
}
