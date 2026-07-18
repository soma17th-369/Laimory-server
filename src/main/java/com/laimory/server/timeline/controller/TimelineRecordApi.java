package com.laimory.server.timeline.controller;

import com.laimory.server.common.ApiResponse;
import com.laimory.server.common.ApiUrls;
import com.laimory.server.timeline.dto.TimelineEventResponse;
import com.laimory.server.timeline.dto.UpdateTimelineEventMemoRequest;
import com.laimory.server.timeline.dto.UpdateTimelineEventRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 확정 타임라인 기록 편집 API의 문서·계약(구현은 {@link TimelineRecordController}).
 * draft 작성 작업(생성·폴링)은 {@link TimelineApi}에 분리 — 여기는 finalize로 만들어진 기록을 다룬다.
 *
 * <p>모든 엔드포인트가 특정 사용자의 기록에 종속되므로 인증 prefix({@code /a/api})에 둔다.
 * 사용자 인증 도입(#108) 전까지는 {@code TimelineDefaults}의 고정 userId를 쓰지만 경로는 인증 prefix로 고정한다.
 *
 * <p>편집은 DRAFT 상태의 하루 기록에서만 허용한다(SAVED는 409). AI 작업 진행(PROCESSING) 중에도
 * 편집은 허용된다 — finalize는 기존 Event를 건드리지 않고 append만 한다.
 *
 * <p>버전은 {@code @PathVariable applicationVersion}으로 받아 그대로 Service에 넘긴다 — 버전별 분기는 Service 책임.
 */
@Tag(name = "Timeline Record", description = "확정 타임라인 기록 편집 — Event 수정·메모")
@SecurityRequirement(name = "bearerAuth")
@RequestMapping(ApiUrls.AUTHENTICATED_API_URL + "/timeline")
public interface TimelineRecordApi {

    @Operation(summary = "타임라인 Event 수정",
            description = "title·subtitle·startAt·endAt 4개 필드를 요청 값으로 전체 교체한다(절대값 대입 — memo·items는 바뀌지 않는다). "
                    + "4개 키를 모두 보내는 계약이다: 키가 하나라도 없으면 400이다. "
                    + "title/startAt의 null은 400, subtitle/endAt은 명시적 null만 '비움'이다"
                    + "(유지할 값은 현재 값을 그대로 보낸다). "
                    + "시간은 보낸 값 그대로 저장한다 — draft 생성의 +10분 충돌 보정이나 하위 Item 시간 변경은 없다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "수정 성공 — 갱신된 Event(하위 items 포함)", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "`ERROR_0400` — 4개 키 중 누락 · title null/공백·255자 초과 · subtitle 255자 초과 · "
                            + "startAt null · endAt이 startAt보다 이전"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "`ERROR_0404` — 이벤트가 없거나 내 소유가 아님(존재 여부는 구분해 주지 않는다)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "`ERROR_1003` — 이벤트가 속한 하루 기록이 이미 SAVED(작성완료) — DRAFT에서만 수정 가능")
    })
    @PatchMapping("/events/{timelineEventId}")
    ResponseEntity<ApiResponse<TimelineEventResponse>> updateTimelineEvent(
            @Parameter(description = "API 버전", example = "v1") @PathVariable String applicationVersion,
            @Parameter(description = "수정할 타임라인 이벤트 ID") @PathVariable Long timelineEventId,
            @RequestBody UpdateTimelineEventRequest request);

    @Operation(summary = "타임라인 Event 메모 작성·수정·제거",
            description = "메모를 요청 값으로 교체하는 단일 endpoint다. memo가 null·공백뿐이거나 필드가 없으면(`{}`) "
                    + "메모를 제거한다. 그 외 문자열은 trim 없이 원문 그대로 저장한다(String.length() 기준 최대 10,000자).")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "반영 성공 — 갱신된 Event(하위 items 포함)", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "`ERROR_0400` — memo가 10,000자 초과"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "`ERROR_0404` — 이벤트가 없거나 내 소유가 아님(존재 여부는 구분해 주지 않는다)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "`ERROR_1003` — 이벤트가 속한 하루 기록이 이미 SAVED(작성완료) — DRAFT에서만 수정 가능")
    })
    @PutMapping("/events/{timelineEventId}/memo")
    ResponseEntity<ApiResponse<TimelineEventResponse>> updateTimelineEventMemo(
            @Parameter(description = "API 버전", example = "v1") @PathVariable String applicationVersion,
            @Parameter(description = "메모를 바꿀 타임라인 이벤트 ID") @PathVariable Long timelineEventId,
            @RequestBody UpdateTimelineEventMemoRequest request);
}
