package com.laimory.server.notice.controller;

import com.laimory.server.common.ApiResponse;
import com.laimory.server.common.ApiUrls;
import com.laimory.server.notice.dto.NoticeListResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 공개 공지사항 조회 API의 문서·계약(구현은 {@link PublicNoticeController}).
 *
 * <p>로그인 전 화면에서도 보여줄 수 있도록 public {@code /api}에 둔다 — bearer requirement가 없다.
 * 공개 계약은 목록 하나다: 각 항목이 원문 page의 {@code contentUrl}을 직접 실으므로 상세 조회가 없다
 * (약관 공개 조회와 같은 구조). 등록·수정·숨김은 localhost 관리자 웹({@code /admin/api/notices})이
 * 소유하며 앱에는 쓰기 API가 없다.
 *
 * <p>버전은 {@code @PathVariable applicationVersion}으로 받아 그대로 Service에 넘긴다 — 버전별 분기는 Service 책임.
 */
@Tag(name = "Notices", description = "공지사항 — 노출 중 공지의 공개 목록 조회")
@RequestMapping(ApiUrls.API_URL + "/notices")
public interface PublicNoticeApi {

    @Operation(summary = "공지 목록 조회",
            description = "노출 중인 공지 전체를 최신 순으로 반환한다. 원문은 응답에 담기지 않는다 — "
                    + "contentUrl은 게시된 공지 page의 HTTPS 주소이며 클라이언트가 WebView로 연다. "
                    + "공지가 없으면 404가 아니라 빈 배열이다. 페이지네이션은 없다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "조회 성공 — `body.notices`는 최신 순(없으면 빈 배열)",
                    useReturnTypeSchema = true)
    })
    @GetMapping
    ResponseEntity<ApiResponse<NoticeListResponse>> getNotices(
            @Parameter(description = "API 버전", example = "v1") @PathVariable String applicationVersion);
}
