package com.laimory.server.notice.controller;

import com.laimory.server.common.ApiResponse;
import com.laimory.server.notice.dto.NoticeListResponse;
import com.laimory.server.notice.dto.NoticeResponse;
import com.laimory.server.notice.service.NoticeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 공개 공지사항 조회 API 구현. HTTP 문서·계약은 {@link PublicNoticeApi}. */
@RestController
@RequiredArgsConstructor
public class PublicNoticeController implements PublicNoticeApi {

    private final NoticeService noticeService;

    @Override
    public ResponseEntity<ApiResponse<NoticeListResponse>> getNotices(String applicationVersion) {
        return ResponseEntity.ok(ApiResponse.success(new NoticeListResponse(
                noticeService.findVisibleNotices(applicationVersion).stream()
                        .map(NoticeResponse::from)
                        .toList())));
    }
}
