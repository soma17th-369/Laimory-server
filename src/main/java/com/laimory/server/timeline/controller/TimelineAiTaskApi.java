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
 * <p>각 단계의 현재 task token을 {@code Task-Token} 헤더로 검증하고, Redis {@code ProcessStage}가
 * 호출 순서를 제한한다. 입력·결과 성공 응답은 다음 단계의 token을 body로 반환한다.
 */
@Tag(name = "Timeline AI Task (서버간)",
        description = "AI 서버 ↔ API 서버 작업 입력·결과(/s/api) — 회전 Task-Token 헤더로 인증")
@RequestMapping(ApiUrls.SERVER_API_URL + "/timeline/drafts")
public interface TimelineAiTaskApi {

    @Operation(summary = "AI 작업 입력 조회",
            description = "AI 추론에 필요한 정규 입력(기록 날짜·timezone·window·source item)을 반환한다. "
                    + "DB 식별자와 사용자 ID는 담지 않으며 source는 rawId로만 식별한다. "
                    + "Task-Token은 dispatch body로 받은 최초 token이다. 성공 응답은 결과 저장용 새 "
                    + "taskToken을 body로 반환하고 Redis token hash와 process stage를 함께 교체한다.")
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
                                                      @Parameter(description = "task 인증 토큰")
                                                      @RequestHeader(value = "Task-Token", required = false)
                                                      String taskToken);

    @Operation(summary = "AI 생성 결과 저장",
            description = "AI가 만든 Event와 채택한 source rawId를 받아 Event/Item/junction 저장과 채택 source "
                    + "삭제를 하나의 트랜잭션으로 커밋한다. Event별 `question`은 선택 필드이며 누락·null·공백은 "
                    + "질문 없음(null)으로 저장한다. 기존 Event는 수정하지 않는다(append-only). "
                    + "Redis token hash와 결과 저장 단계를 선점한 요청만 graph를 쓰며, 성공 응답은 "
                    + "callback용 새 taskToken을 body로 반환한다. "
                    + "작업 상태 전이(SUCCESS/FAILED)는 이 API가 아니라 콜백 API가 기록한다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "저장 완료 + callback용 `taskToken`"),
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
                                @Parameter(description = "task 인증 토큰")
                                @RequestHeader(value = "Task-Token", required = false)
                                String taskToken,
                                @RequestBody AiTimelineResultRequest request);
}
