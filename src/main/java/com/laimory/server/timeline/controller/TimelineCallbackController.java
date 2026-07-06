package com.laimory.server.timeline.controller;

import com.laimory.server.common.ApiResponse;
import com.laimory.server.common.ApiUrls;
import com.laimory.server.timeline.dto.DraftTaskCallbackRequest;
import com.laimory.server.timeline.service.TimelineCallbackService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 카드 생성 콜백(서버간 통신). task별 one-time Callback-Token 헤더로 검증한다(서비스가 해시 비교; 누락/불일치 401).
 *
 * <p>공개 API와 prefix가 다르므로(/s/api vs /api) 별도 컨트롤러로 두고 클래스 레벨 {@code @RequestMapping}을 쓴다.
 */
@Tag(name = "Timeline Callback (서버간)",
        description = "AI 서버 → API 서버 콜백(/s/api) — task별 one-time Callback-Token 헤더로 인증")
@RestController
@RequiredArgsConstructor
@RequestMapping(ApiUrls.SERVER_API_URL + "/timeline/drafts")
public class TimelineCallbackController {

    private final TimelineCallbackService timelineCallbackService;

    @Operation(summary = "AI draft 생성 결과 콜백",
            description = "AI 서버가 draft 생성 결과(SUCCESS의 events 또는 FAILED의 실패 코드)를 전달한다. "
                    + "Callback-Token은 작업 dispatch 시 AI에 전달된 one-time 토큰으로, 한 번 소비되면 재사용할 수 없다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "콜백 처리 완료(본문 없음)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "`ERROR_0400` — 불량 콜백 바디(미지원 status 등)",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "`ERROR_1002`(토큰 누락·불일치) · `ERROR_1012`(이미 사용된 토큰)",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "`ERROR_1001` — 작업 없음(만료 포함)",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    @PostMapping("/{taskId}/callback")
    public ResponseEntity<Void> callback(@Parameter(description = "API 버전", example = "v1")
                                         @PathVariable String applicationVersion,
                                         @Parameter(description = "draft 작업 ID")
                                         @PathVariable String taskId,
                                         @Parameter(description = "task별 one-time 콜백 인증 토큰")
                                         @RequestHeader(value = "Callback-Token", required = false)
                                         String callbackToken,
                                         @RequestBody DraftTaskCallbackRequest request) {
        timelineCallbackService.handleCallback(applicationVersion, taskId, callbackToken, request);
        return ResponseEntity.ok().build();
    }
}
