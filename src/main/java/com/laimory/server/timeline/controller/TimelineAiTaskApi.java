package com.laimory.server.timeline.controller;

import com.laimory.server.common.ApiUrls;
import com.laimory.server.timeline.dto.AiTimelineResultRequest;
import com.laimory.server.timeline.dto.AiTimelineResultResponse;
import com.laimory.server.timeline.dto.AiTimelineTaskInputResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * AI 작업 입력 조회·결과 저장(서버간 통신)의 문서·계약(구현은 {@link TimelineAiTaskController}).
 * 결과 상태 전이는 이 API가 아니라 {@link TimelineCallbackApi}가 담당한다.
 *
 * <p>단계별 토큰 chain으로 인증한다: dispatch가 준 입력 토큰으로 입력을 조회하면 결과 저장 토큰이,
 * 결과를 저장하면 콜백 토큰이 응답에 실린다. 각 단계는 몇 번 재시도해도 같은 다음 토큰을 돌려주므로
 * 응답 유실이 task를 고립시키지 않는다.
 */
@Tag(name = "Timeline AI Task (서버간)",
        description = "AI 서버 ↔ API 서버 작업 입력·결과(/s/api) — 단계별 Task-Token 헤더로 인증")
@RequestMapping(ApiUrls.SERVER_API_URL + "/timeline/drafts")
public interface TimelineAiTaskApi {

    @Operation(summary = "AI 작업 입력 조회",
            description = "AI 추론에 필요한 정규 입력(기록 날짜·timezone·window·source item)을 반환한다. "
                    + "DB 식별자와 사용자 ID는 담지 않으며 source는 rawId로만 식별한다. "
                    + "Task-Token은 dispatch body로 전달된 입력 토큰이며, 검증에 성공하면 응답 `resultToken`으로 "
                    + "다음 단계 토큰을 돌려주고 작업 TTL을 다시 확보한다. 같은 토큰으로 재조회해도 같은 값이다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "입력 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "`-1002` — 토큰 누락·불일치"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "`-1001` — 작업 없음(만료 포함), `-404` — 작업의 하루 기록 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "`-1017` — 이미 종결된 작업")
    })
    @GetMapping("/{taskId}/input")
    ResponseEntity<AiTimelineTaskInputResponse> input(@Parameter(description = "API 버전", example = "v1")
                                                      @PathVariable String applicationVersion,
                                                      @Parameter(description = "draft 작업 ID")
                                                      @PathVariable String taskId,
                                                      @Parameter(description = "입력 조회 단계 토큰")
                                                      @RequestHeader(value = "Task-Token", required = false)
                                                      String taskToken);

    @Operation(summary = "AI 생성 결과 저장",
            description = "AI가 만든 Event와 채택한 source rawId를 받아 Event/Item/junction 저장과 채택 source "
                    + "삭제를 하나의 트랜잭션으로 커밋한다. 기존 Event는 수정하지 않는다(append-only). "
                    + "같은 작업의 결과가 이미 저장돼 있으면 graph를 건드리지 않고 성공으로 응답한다 — "
                    + "응답 유실 후 재시도가 중복 저장을 만들지 않는다. "
                    + "작업 상태 전이(SUCCESS/FAILED)는 이 API가 아니라 콜백 API가 기록한다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "저장 완료(또는 이미 반영된 재시도) — 콜백 단계 토큰 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "`-400` — 결과 계약 위반(빈 결과, 필수 필드 누락, 이 작업의 source가 아닌 rawId 등)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "`-1002` — 토큰 누락·불일치"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "`-1001` — 작업 없음(만료 포함), `-404` — 작업의 하루 기록 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "`-1017` — 이미 종결된 작업, `-1003` — 이미 저장(SAVED)된 하루 기록")
    })
    @PostMapping("/{taskId}/result")
    ResponseEntity<AiTimelineResultResponse> result(@Parameter(description = "API 버전", example = "v1")
                                                    @PathVariable String applicationVersion,
                                                    @Parameter(description = "draft 작업 ID")
                                                    @PathVariable String taskId,
                                                    @Parameter(description = "결과 저장 단계 토큰")
                                                    @RequestHeader(value = "Task-Token", required = false)
                                                    String taskToken,
                                                    @RequestBody AiTimelineResultRequest request);
}
