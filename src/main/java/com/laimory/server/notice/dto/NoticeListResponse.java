package com.laimory.server.notice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 노출 중 공지 목록({@code GET /api/{version}/notices}) — 최신 순. 공지가 없으면 404가 아니라 빈 배열이다. */
@Schema(description = "공지 목록 응답")
public record NoticeListResponse(
        @Schema(description = "노출 중 공지(최신 순) — 없으면 빈 배열") List<NoticeResponse> notices
) {
}
