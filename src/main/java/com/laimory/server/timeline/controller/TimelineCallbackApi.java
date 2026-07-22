package com.laimory.server.timeline.controller;

import com.laimory.server.common.ApiResponse;
import com.laimory.server.common.ApiUrls;
import com.laimory.server.timeline.dto.DraftTaskCallbackRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * AI 타임라인 이벤트 생성 콜백(서버간 통신)의 문서·계약(구현은 {@link TimelineCallbackController}).
 * task별 one-time Callback-Token 헤더로 검증한다.
 *
 * <p>공개 API와 prefix가 다르므로(/s/api vs /api) 별도 컨트롤러로 두고 클래스 레벨 {@code @RequestMapping}을 쓴다.
 */
@Tag(name = "Timeline Callback (서버간)",
        description = "AI 서버 → API 서버 콜백(/s/api) — task별 one-time Callback-Token 헤더로 인증")
@RequestMapping(ApiUrls.SERVER_API_URL + "/timeline/drafts")
public interface TimelineCallbackApi {

    @Operation(summary = "AI draft 생성 결과 콜백",
            description = "AI 서버가 final Event/Item/junction direct-write commit 뒤 결과 상태(status/errorCode/error)를 "
                    + "알린다. 결과 graph는 body로 보내지 않는다(서버는 상태 전이만 기록). "
                    + "Callback-Token은 작업 dispatch body로 AI에 전달된 task별 토큰이다 — commit 후 네트워크 오류로 "
                    + "반복된 유효한 재콜백은 terminal no-op 200으로 멱등 흡수된다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "콜백 처리 완료(본문 없음 — terminal 재콜백 멱등 흡수 포함)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "`ERROR_0400` — 불량 콜백 바디(미지원 status 등)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "`ERROR_1002` — 토큰 누락·불일치"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "`ERROR_1001` — 작업 없음(만료 포함)")
    })
    @PostMapping("/{taskId}/callback")
    ResponseEntity<Void> callback(@Parameter(description = "API 버전", example = "v1")
                                  @PathVariable String applicationVersion,
                                  @Parameter(description = "draft 작업 ID")
                                  @PathVariable String taskId,
                                  @Parameter(description = "task별 one-time 콜백 인증 토큰")
                                  @RequestHeader(value = "Callback-Token", required = false)
                                  String callbackToken,
                                  @RequestBody DraftTaskCallbackRequest request);
}
