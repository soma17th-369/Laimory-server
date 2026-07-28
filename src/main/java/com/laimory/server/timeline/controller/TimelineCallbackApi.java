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
 * 단계별 토큰 chain의 마지막 토큰을 Callback-Token 헤더로 검증한다. 결과 데이터 저장은 이 API가 아니라
 * {@link TimelineAiTaskApi}가 담당한다.
 *
 * <p>공개 API와 prefix가 다르므로(/s/api vs /api) 별도 컨트롤러로 두고 클래스 레벨 {@code @RequestMapping}을 쓴다.
 */
@Tag(name = "Timeline Callback (서버간)",
        description = "AI 서버 → API 서버 콜백(/s/api) — 단계별 토큰 chain의 콜백 토큰으로 인증")
@RequestMapping(ApiUrls.SERVER_API_URL + "/timeline/drafts")
public interface TimelineCallbackApi {

    @Operation(summary = "AI draft 생성 결과 콜백",
            description = "AI 서버가 결과 저장을 마친 뒤(또는 실패했을 때) 작업 상태(status/errorCode/error)를 알린다. "
                    + "서버는 Redis 상태 전이와 완료 푸시만 수행하며 결과 graph는 body로 받지 않는다. "
                    + "Callback-Token은 결과 저장 응답으로 받은 콜백 토큰이다 — 실패 보고(FAILED)는 결과 저장 단계를 "
                    + "거치지 않으므로 결과 저장 단계 토큰도 허용한다. "
                    + "SUCCESS는 해당 작업의 결과가 실제로 저장돼 있을 때만 받는다. "
                    + "같은 결과의 재전송은 그대로 성공 응답한다(재시도 안전).")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "상태 전이 완료 또는 같은 결과의 재전송(본문 없음)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "`-400` — 불량 콜백 바디(미지원 status 등)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "`-1002` — 토큰 누락·불일치(SUCCESS에 결과 저장 단계 토큰을 쓴 경우 포함)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "`-1001` — 작업 없음(만료 포함)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "`-1017` — 이미 종결된 작업에 상충 결과 전송, 또는 결과 저장 없이 도착한 SUCCESS")
    })
    @PostMapping("/{taskId}/callback")
    ResponseEntity<Void> callback(@Parameter(description = "API 버전", example = "v1")
                                  @PathVariable String applicationVersion,
                                  @Parameter(description = "draft 작업 ID")
                                  @PathVariable String taskId,
                                  @Parameter(description = "콜백 단계 인증 토큰(FAILED는 결과 저장 단계 토큰도 허용)")
                                  @RequestHeader(value = "Callback-Token", required = false)
                                  String callbackToken,
                                  @RequestBody DraftTaskCallbackRequest request);
}
