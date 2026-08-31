package com.laimory.server.terms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 요청 종류별 현재 유효 약관 목록({@code GET /api/{version}/terms?termTypes=A&termTypes=B}) — 클라이언트가
 * 요청한 순서로 정렬된다. 현재 유효 문서가 없으면(활성화 전 rollout 상태) 404가 아니라 빈 배열이다.
 */
@Schema(description = "현재 유효 약관 목록 응답")
public record TermListResponse(
        @Schema(description = "현재 유효 약관(요청한 termTypes 순서) — 활성화 전이면 빈 배열") List<TermResponse> terms
) {
}
