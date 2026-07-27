package com.laimory.server.appconfig;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.laimory.server.config.SecurityConfig;
import com.laimory.server.testsupport.AuthTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * /intro 컨트롤러 슬라이스 테스트(MockMvc). 성공 응답 envelope(header.code + body) 계약을 고정한다. 인프라 0.
 */
@WebMvcTest(AppConfigController.class)
@Import({SecurityConfig.class, AuthTestSupport.JwtTokensTestConfig.class})
class AppConfigControllerTest {

    private static final String INTRO = "/api/v1/intro";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AppConfigService appConfigService;

    @Test
    void intro_returns200WithEnvelope() throws Exception {
        when(appConfigService.getAppConfig(any()))
                .thenReturn(new AppConfigResponse(1L, 2L, "msg"));

        mockMvc.perform(get(INTRO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.code").value(0))
                .andExpect(jsonPath("$.header.message").value(""))
                .andExpect(header().exists("Transaction-Id"))
                .andExpect(jsonPath("$.header.transactionId").doesNotExist()) // 노출 채널은 헤더뿐(hard cut 회귀 방지)
                .andExpect(jsonPath("$.body.minAppVersion").value(1))
                .andExpect(jsonPath("$.body.recommendAppVersion").value(2))
                .andExpect(jsonPath("$.body.debugTestMessage").value("msg"));
    }
}
