package com.laimory.server.notice.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.config.SecurityConfig;
import com.laimory.server.notice.entity.Notice;
import com.laimory.server.notice.service.NoticeService;
import com.laimory.server.testsupport.AuthTestSupport;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 공개 공지 조회 컨트롤러 슬라이스 테스트(MockMvc). 무인증 200(public 계약)·목록에 본문 미포함·
 * 없음/숨김 404 은닉·비숫자 ID 400 wire 계약을 검증한다. 인프라 0.
 */
@WebMvcTest(PublicNoticeController.class)
@Import({SecurityConfig.class, AuthTestSupport.JwtTokensTestConfig.class})
class PublicNoticeControllerTest {

    private static final String PATH = "/api/v1/notices";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private NoticeService noticeService;

    @Test
    void getNoticesWithoutBearerReturnsNewestFirstWithoutBody() throws Exception {
        when(noticeService.findVisibleNotices("v1")).thenReturn(List.of(
                notice(12L, "둘째 공지", "둘째 본문", LocalDateTime.of(2026, 9, 24, 10, 30)),
                notice(7L, "첫째 공지", "첫째 본문", LocalDateTime.of(2026, 9, 1, 9, 0))));

        mockMvc.perform(get(PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.code").value(0))
                .andExpect(header().exists("Transaction-Id"))
                .andExpect(jsonPath("$.body.notices.length()").value(2))
                .andExpect(jsonPath("$.body.notices[0].noticeId").value(12))
                .andExpect(jsonPath("$.body.notices[0].title").value("둘째 공지"))
                .andExpect(jsonPath("$.body.notices[0].publishedAt").value("2026-09-24T10:30:00"))
                .andExpect(jsonPath("$.body.notices[0].body").doesNotExist())
                .andExpect(jsonPath("$.body.notices[0].hidden").doesNotExist())
                .andExpect(jsonPath("$.body.notices[1].noticeId").value(7));
    }

    @Test
    void getNoticesWithNoVisibleNoticeReturns200WithEmptyArray() throws Exception {
        when(noticeService.findVisibleNotices("v1")).thenReturn(List.of());

        mockMvc.perform(get(PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.code").value(0))
                .andExpect(jsonPath("$.body.notices").isArray())
                .andExpect(jsonPath("$.body.notices").isEmpty());
    }

    @Test
    void getNoticeReturnsBodyTextAsIs() throws Exception {
        when(noticeService.getVisibleNotice("v1", 12L))
                .thenReturn(notice(12L, "점검 안내", "첫 줄\n둘째 줄", LocalDateTime.of(2026, 9, 24, 10, 30)));

        mockMvc.perform(get(PATH + "/12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.code").value(0))
                .andExpect(jsonPath("$.body.noticeId").value(12))
                .andExpect(jsonPath("$.body.title").value("점검 안내"))
                .andExpect(jsonPath("$.body.body").value("첫 줄\n둘째 줄"))
                .andExpect(jsonPath("$.body.publishedAt").value("2026-09-24T10:30:00"))
                .andExpect(jsonPath("$.body.hidden").doesNotExist());
    }

    @Test
    void getNoticeMissingOrHiddenReturns404() throws Exception {
        when(noticeService.getVisibleNotice("v1", 99L))
                .thenThrow(new BusinessException(ExceptionType.RESOURCE_NOT_FOUND));

        mockMvc.perform(get(PATH + "/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.code").value(-404))
                .andExpect(jsonPath("$.body").doesNotExist());
    }

    @Test
    void getNoticeWithNonNumericIdReturns400() throws Exception {
        mockMvc.perform(get(PATH + "/latest"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.code").value(-400));

        verifyNoInteractions(noticeService);
    }

    /** ID·게시 시각은 DB가 채우는 값이라 슬라이스 fixture가 직접 심는다. */
    private static Notice notice(long noticeId, String title, String body, LocalDateTime createdAt) {
        Notice notice = Notice.of(title, body);
        ReflectionTestUtils.setField(notice, "noticeId", noticeId);
        ReflectionTestUtils.setField(notice, "createdAt", createdAt);
        return notice;
    }
}
