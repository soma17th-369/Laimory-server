package com.laimory.server.timeline.controller;

import com.laimory.server.common.ApiResponse;
import com.laimory.server.common.ApiUrls;
import com.laimory.server.timeline.dto.DailyTimelineResponse;
import com.laimory.server.timeline.dto.DailyTimelinesResponse;
import com.laimory.server.timeline.dto.UpdateTimelineEventMemoRequest;
import com.laimory.server.timeline.dto.UpdateTimelineEventRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 확정 타임라인 기록 조회·편집 API의 문서·계약(구현은 {@link TimelineRecordController}).
 * draft 작성 작업(생성·폴링)은 {@link TimelineApi}에 분리 — 여기는 finalize로 만들어진 기록을 다룬다.
 *
 * <p>모든 엔드포인트가 특정 사용자의 기록에 종속되므로 인증 prefix({@code /a/api})에 둔다.
 * userId는 인증된 JWT principal에서 받으며 클라이언트 입력이 아니다 — OpenAPI parameter로 노출하지 않는다.
 *
 * <p>편집은 DRAFT 상태의 하루 기록에서만 허용한다(SAVED는 409). AI 작업 진행(PROCESSING) 중 Event/memo
 * 필드만 바꾸는 PATCH는 허용하지만, {@code photosToAdd}가 있으면 같은 날짜 graph writer와 충돌하므로 409다.
 *
 * <p>버전은 {@code @PathVariable applicationVersion}으로 받아 그대로 Service에 넘긴다 — 버전별 분기는 Service 책임.
 */
@Tag(name = "Timeline Record", description = "확정 타임라인 기록 조회·편집 — DailyRecord 조회·삭제, Event 수정·메모·삭제")
@SecurityRequirement(name = "bearerAuth")
@RequestMapping(ApiUrls.AUTHENTICATED_API_URL + "/timeline")
public interface TimelineRecordApi {

    @Operation(summary = "사용자 타임라인 전체 조회",
            description = "인증 사용자의 모든 DRAFT/SAVED 하루 기록을 최신 날짜부터 반환한다. "
                    + "Event가 없는 기록도 포함하며, 기록이 없으면 timelines 빈 배열을 반환한다. "
                    + "각 Event의 연결 Item은 events[].items[]에 포함한다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "전체 조회 성공", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "`-2001` — 인증 필요(Bearer access token 부재/무효/만료)")
    })
    @GetMapping("/daily-records")
    ResponseEntity<ApiResponse<DailyTimelinesResponse>> getDailyTimelines(
            @Parameter(description = "API 버전", example = "v1") @PathVariable String applicationVersion,
            @Parameter(hidden = true) @AuthenticationPrincipal(errorOnInvalidType = true) Long userId);

    @Operation(summary = "하루 타임라인 단건 조회",
            description = "인증 사용자가 소유한 하루 기록과 Event·Item graph를 반환한다. "
                    + "기록이 없거나 다른 사용자 소유이면 존재 여부를 구분하지 않고 404로 응답한다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "단건 조회 성공 — Event별 연결 Item 포함", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "`-2001` — 인증 필요(Bearer access token 부재/무효/만료)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "`-404` — 하루 기록이 없거나 내 소유가 아님(존재 여부는 구분해 주지 않는다)")
    })
    @GetMapping("/daily-records/{dailyRecordId}")
    ResponseEntity<ApiResponse<DailyTimelineResponse>> getDailyTimeline(
            @Parameter(description = "API 버전", example = "v1") @PathVariable String applicationVersion,
            @Parameter(hidden = true) @AuthenticationPrincipal(errorOnInvalidType = true) Long userId,
            @Parameter(description = "조회할 하루 기록 ID") @PathVariable Long dailyRecordId);

    @Operation(summary = "타임라인 Event 수정",
            description = "title·subtitle·startAt·endAt 4개 필드를 요청 값으로 전체 교체하고 optional memo와 PHOTO 추가를 함께 처리한다. "
                    + "4개 키를 모두 보내는 계약이다: 키가 하나라도 없으면 400이다. "
                    + "title/startAt의 null은 400, subtitle/endAt은 명시적 null만 '비움'이다"
                    + "(유지할 값은 현재 값을 그대로 보낸다). "
                    + "eventType은 optional이다 — 누락이면 현재 분류를 유지하고, "
                    + "보내면 허용 literal로 교체한다(명시적 null·미지원 값은 400). "
                    + "memo는 누락 시 유지, null/공백이면 제거, 그 외 원문 저장이다. "
                    + "photosToAdd는 누락/빈 배열이면 변경 없고 명시적 null이면 400이며, PHOTO만 append한다. "
                    + "클라이언트가 S3 업로드 성공을 확인한 뒤 호출해야 한다. 서버는 S3 존재 여부를 확인하지 않고, "
                    + "description은 저장하지 않으며 photoUrl은 인증 사용자와 filename으로 생성한다. "
                    + "같은 rawId 재시도는 no-op 또는 같은 record PHOTO 재사용으로 수렴한다. "
                    + "시간은 보낸 값 그대로 저장한다 — draft 생성의 +10분 충돌 보정은 없다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "수정 성공(body=null)", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "`-400` — 4개 키 중 누락 · title null/공백·255자 초과 · subtitle 255자 초과 · "
                            + "startAt null · endAt이 startAt보다 이전 · eventType 명시적 null/미지원 literal · "
                            + "memo 10,000자 초과 · photosToAdd null/PHOTO 입력 오류/rawId·filename 충돌 · "
                            + "`-1004` — 사진 수 초과"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "`-2001` — 인증 필요(Bearer access token 부재/무효/만료)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "`-404` — 이벤트가 없거나 내 소유가 아님(존재 여부는 구분해 주지 않는다)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "`-1003` — 이벤트가 속한 하루 기록이 이미 SAVED(작성완료) — DRAFT에서만 수정 가능 · "
                            + "`-1016` — non-empty photosToAdd가 있고 같은 날짜 AI/사진추가/삭제가 진행 중")
    })
    @PatchMapping("/events/{timelineEventId}")
    ResponseEntity<ApiResponse<Void>> updateTimelineEvent(
            @Parameter(description = "API 버전", example = "v1") @PathVariable String applicationVersion,
            @Parameter(hidden = true) @AuthenticationPrincipal(errorOnInvalidType = true) Long userId,
            @Parameter(description = "수정할 타임라인 이벤트 ID") @PathVariable Long timelineEventId,
            @RequestBody UpdateTimelineEventRequest request);

    @Operation(summary = "타임라인 Event 메모 작성·수정·제거",
            description = "메모를 요청 값으로 교체하는 단일 endpoint다. memo가 null·공백뿐이거나 필드가 없으면(`{}`) "
                    + "메모를 제거한다. 그 외 문자열은 trim 없이 원문 그대로 저장한다(String.length() 기준 최대 10,000자).")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "반영 성공(body=null)", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "`-400` — memo가 10,000자 초과"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "`-2001` — 인증 필요(Bearer access token 부재/무효/만료)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "`-404` — 이벤트가 없거나 내 소유가 아님(존재 여부는 구분해 주지 않는다)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "`-1003` — 이벤트가 속한 하루 기록이 이미 SAVED(작성완료) — DRAFT에서만 수정 가능")
    })
    @PutMapping("/events/{timelineEventId}/memo")
    ResponseEntity<ApiResponse<Void>> updateTimelineEventMemo(
            @Parameter(description = "API 버전", example = "v1") @PathVariable String applicationVersion,
            @Parameter(hidden = true) @AuthenticationPrincipal(errorOnInvalidType = true) Long userId,
            @Parameter(description = "메모를 바꿀 타임라인 이벤트 ID") @PathVariable Long timelineEventId,
            @RequestBody UpdateTimelineEventMemoRequest request);

    @Operation(summary = "타임라인 Event 삭제",
            description = "Event와 마지막 참조가 사라지는 non-PHOTO Item을 DB에서 삭제한다. 마지막 Event를 지워도 하루 기록"
                    + "(DailyRecord)은 유지된다 — 하루 전체 제거는 DailyRecord 삭제 API가 담당한다. "
                    + "마지막 참조가 사라지는 PHOTO Item은 S3 삭제 작업과 함께 보존하며, commit 뒤 별도 "
                    + "worker가 S3 성공 시 Item과 작업을 최종 삭제한다. 따라서 200은 root 삭제와 PHOTO "
                    + "정리 작업 등록 성공을 뜻하고 S3 삭제 완료를 기다리지 않는다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "삭제 성공(body 없음)", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "`-2001` — 인증 필요(Bearer access token 부재/무효/만료)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "`-404` — 이벤트가 없거나 내 소유가 아님(존재 여부는 구분해 주지 않는다)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "`-1003` — 이벤트가 속한 하루 기록이 이미 SAVED(작성완료) · "
                            + "`-1016` — 같은 날짜의 AI 작업/사진추가/삭제가 진행 중(잠시 후 재시도)")
    })
    @DeleteMapping("/events/{timelineEventId}")
    ResponseEntity<ApiResponse<Void>> deleteTimelineEvent(
            @Parameter(description = "API 버전", example = "v1") @PathVariable String applicationVersion,
            @Parameter(hidden = true) @AuthenticationPrincipal(errorOnInvalidType = true) Long userId,
            @Parameter(description = "삭제할 타임라인 이벤트 ID") @PathVariable Long timelineEventId);

    @Operation(summary = "하루 기록(DailyRecord) 삭제",
            description = "하루 전체 Record·Events와 마지막 참조가 사라지는 non-PHOTO Items를 DB에서 삭제한다. "
                    + "마지막 참조가 사라지는 PHOTO Item은 S3 삭제 작업과 함께 보존하며, commit 뒤 별도 "
                    + "worker가 S3 성공 시 Item과 작업을 최종 삭제한다. 따라서 200은 root 삭제와 PHOTO "
                    + "정리 작업 등록 성공을 뜻하고 S3 삭제 완료를 기다리지 않는다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "삭제 성공(body 없음)", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "`-2001` — 인증 필요(Bearer access token 부재/무효/만료)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "`-404` — 하루 기록이 없거나 내 소유가 아님(존재 여부는 구분해 주지 않는다)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "`-1003` — 하루 기록이 이미 SAVED(작성완료) · "
                            + "`-1016` — 같은 날짜의 AI 작업/사진추가/삭제가 진행 중(잠시 후 재시도)")
    })
    @DeleteMapping("/daily-records/{dailyRecordId}")
    ResponseEntity<ApiResponse<Void>> deleteDailyRecord(
            @Parameter(description = "API 버전", example = "v1") @PathVariable String applicationVersion,
            @Parameter(hidden = true) @AuthenticationPrincipal(errorOnInvalidType = true) Long userId,
            @Parameter(description = "삭제할 하루 기록 ID") @PathVariable Long dailyRecordId);
}
