package com.laimory.server.credit.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.laimory.server.config.SecurityConfig;
import com.laimory.server.credit.dto.CreditCostsResponse;
import com.laimory.server.credit.service.CreditService;
import com.laimory.server.testsupport.AuthTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 공개 크레딧 비용 조회 컨트롤러 슬라이스(MockMvc) — 무인증 200(public 계약)과 응답 필드명을 검증한다. 인프라 0.
 */
@WebMvcTest(PublicCreditCostController.class)
@Import({SecurityConfig.class, AuthTestSupport.JwtTokensTestConfig.class})
class PublicCreditCostControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CreditService creditService;

    @Test
    void getCostsWithoutBearerReturnsTimelineCreationCost() throws Exception {
        when(creditService.getCosts("v1")).thenReturn(new CreditCostsResponse(2));

        mockMvc.perform(get("/api/v1/credit/costs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.code").value(0))
                .andExpect(jsonPath("$.body.timelineCreation").value(2));
    }
}
