package com.laimory.server.notice.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.config.SecurityConfig;
import com.laimory.server.notice.dto.NoticeResponse;
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
 * 공개 공지 조회 컨트롤러 슬라이스 테스트(MockMvc). 무인증 200(public 계약)과 원문 대신 항목별
 * contentUrl만 나가는 wire 계약, 단건 조회의 404·400 매핑을 검증한다. 인프라 0.
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
    void getNoticesWithoutBearerReturnsNewestFirstWithContentUrl() throws Exception {
        when(noticeService.findVisibleNotices("v1")).thenReturn(List.of(
                notice(12L, "둘째 공지", "https://www.laimory.app/notices/12", LocalDateTime.of(2026, 9, 24, 10, 30)),
                notice(7L, "첫째 공지", "https://www.laimory.app/notices/7", LocalDateTime.of(2026, 9, 1, 9, 0))));

        mockMvc.perform(get(PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.code").value(0))
                .andExpect(header().exists("Transaction-Id"))
                .andExpect(jsonPath("$.body.notices.length()").value(2))
                .andExpect(jsonPath("$.body.notices[0].noticeId").value(12))
                .andExpect(jsonPath("$.body.notices[0].title").value("둘째 공지"))
                .andExpect(jsonPath("$.body.notices[0].contentUrl").value("https://www.laimory.app/notices/12"))
                .andExpect(jsonPath("$.body.notices[0].publishedAt").value("2026-09-24T10:30:00"))
                // 원문·노출 상태는 wire에 없다 — 본문은 contentUrl page가 소유한다.
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
    void getNoticeWithoutBearerReturnsSingleNoticeWithContentUrl() throws Exception {
        when(noticeService.findVisibleNotice("v1", 12L)).thenReturn(
                notice(12L, "팝업 공지", "https://www.laimory.app/notices/12", LocalDateTime.of(2026, 10, 6, 9, 0)));

        mockMvc.perform(get(PATH + "/12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.code").value(0))
                .andExpect(jsonPath("$.body.noticeId").value(12))
                .andExpect(jsonPath("$.body.title").value("팝업 공지"))
                .andExpect(jsonPath("$.body.contentUrl").value("https://www.laimory.app/notices/12"))
                .andExpect(jsonPath("$.body.publishedAt").value("2026-10-06T09:00:00"))
                // 숨김·팝업 지정은 관리자 상태라 공개 wire에 없다.
                .andExpect(jsonPath("$.body.hidden").doesNotExist())
                .andExpect(jsonPath("$.body.popup").doesNotExist());
    }

    @Test
    void getNoticeHiddenOrMissingReturns404() throws Exception {
        when(noticeService.findVisibleNotice("v1", 9L))
                .thenThrow(new BusinessException(ExceptionType.RESOURCE_NOT_FOUND));

        mockMvc.perform(get(PATH + "/9"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.code").value(-404));
    }

    @Test
    void getNoticeWithNonNumericIdReturns400() throws Exception {
        mockMvc.perform(get(PATH + "/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.code").value(-400));
    }

    /** ID·게시 시각은 DB가 채우는 값이라 슬라이스 fixture가 직접 심고, 서비스와 같은 변환으로 응답을 만든다. */
    private static NoticeResponse notice(long noticeId, String title, String contentUrl, LocalDateTime createdAt) {
        Notice notice = Notice.of(title, contentUrl);
        ReflectionTestUtils.setField(notice, "noticeId", noticeId);
        ReflectionTestUtils.setField(notice, "createdAt", createdAt);
        return NoticeResponse.from(notice);
    }
}
