package com.laimory.server.timeline.controller;

import com.laimory.server.common.ApiResponse;
import com.laimory.server.common.ApiUrls;
import com.laimory.server.timeline.dto.DailyTimelineResponse;
import com.laimory.server.timeline.dto.DailyTimelinesResponse;
import com.laimory.server.timeline.dto.TimelineEventResponse;
import com.laimory.server.timeline.dto.UpdateTimelineEventMemoRequest;
import com.laimory.server.timeline.dto.UpdateTimelineEventRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
 * <p>편집은 DRAFT 상태의 하루 기록에서만 허용한다(SAVED는 409). AI 작업 진행(PROCESSING)만으로
 * Event/memo/PHOTO 변경 요청을 거절하지 않는다.
 *
 * <p>버전은 {@code @PathVariable applicationVersion}으로 받아 그대로 Service에 넘긴다 — 버전별 분기는 Service 책임.
 */
@Tag(name = "Timeline Record", description = "확정 타임라인 기록 조회·편집 — DailyRecord 조회·삭제, Event 조회·수정·메모·삭제")
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

    @Operation(summary = "하루 타임라인 단건 조회(ID, deprecated)", deprecated = true,
            description = "호환을 위해 한시적으로 유지하는 ID 기반 조회다. 신규 클라이언트는 "
                    + "GET /daily-records/{recordDate}를 사용한다. 인증 사용자가 소유한 하루 기록과 "
                    + "Event·Item graph를 반환하며, 기록이 없거나 다른 사용자 소유이면 404로 응답한다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "단건 조회 성공 — Event별 연결 Item 포함", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "`-2001` — 인증 필요(Bearer access token 부재/무효/만료)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "`-404` — 하루 기록이 없거나 내 소유가 아님(존재 여부는 구분해 주지 않는다)")
    })
    @GetMapping("/daily-records/by-id/{dailyRecordId}")
    ResponseEntity<ApiResponse<DailyTimelineResponse>> getDailyTimeline(
            @Parameter(description = "API 버전", example = "v1") @PathVariable String applicationVersion,
            @Parameter(hidden = true) @AuthenticationPrincipal(errorOnInvalidType = true) Long userId,
            @Parameter(description = "조회할 하루 기록 ID") @PathVariable Long dailyRecordId);

    @Operation(summary = "하루 타임라인 날짜 조회",
            description = "인증 사용자가 선택한 날짜의 하루 기록과 Event·Item graph를 반환한다. recordDate는 "
                    + "yyyy-MM-dd 형식이며 서버에서 계산·timezone 보정하지 않는다. 기록이 없으면 404로 응답한다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "날짜 조회 성공 — Event별 연결 Item 포함", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "`-400` — recordDate가 올바른 ISO 날짜 형식이 아님"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "`-2001` — 인증 필요(Bearer access token 부재/무효/만료)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "`-404` — 해당 날짜의 내 하루 기록이 없음")
    })
    @GetMapping("/daily-records/{recordDate}")
    ResponseEntity<ApiResponse<DailyTimelineResponse>> getDailyTimelineByDate(
            @Parameter(description = "API 버전", example = "v1") @PathVariable String applicationVersion,
            @Parameter(hidden = true) @AuthenticationPrincipal(errorOnInvalidType = true) Long userId,
            @Parameter(description = "조회할 기록 날짜", example = "2026-07-08")
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate recordDate);

    @Operation(summary = "타임라인 Event 단건 조회",
            description = "인증 사용자가 소유한 Event와 연결 Item을 반환한다. DRAFT/SAVED 모두 조회할 수 있으며, "
                    + "Event가 없거나 부모 DailyRecord가 없거나 다른 사용자 소유이면 모두 404로 응답한다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "Event 단건 조회 성공 — 연결 Item 포함", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "`-2001` — 인증 필요(Bearer access token 부재/무효/만료)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "`-404` — 이벤트가 없거나 내 소유가 아님(존재 여부는 구분해 주지 않는다)")
    })
    @GetMapping("/events/{timelineEventId}")
    ResponseEntity<ApiResponse<TimelineEventResponse>> getTimelineEvent(
            @Parameter(description = "API 버전", example = "v1") @PathVariable String applicationVersion,
            @Parameter(hidden = true) @AuthenticationPrincipal(errorOnInvalidType = true) Long userId,
            @Parameter(description = "조회할 타임라인 이벤트 ID") @PathVariable Long timelineEventId);

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
                            + "memo 500자 초과 · photosToAdd null/PHOTO 입력 오류/rawId·filename 충돌 · "
                            + "`-1004` — 사진 수 초과"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "`-2001` — 인증 필요(Bearer access token 부재/무효/만료)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "`-404` — 이벤트가 없거나 내 소유가 아님(존재 여부는 구분해 주지 않는다)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "`-1003` — 이벤트가 속한 하루 기록이 이미 SAVED(작성완료) — DRAFT에서만 수정 가능")
    })
    @PatchMapping("/events/{timelineEventId}")
    ResponseEntity<ApiResponse<Void>> updateTimelineEvent(
            @Parameter(description = "API 버전", example = "v1") @PathVariable String applicationVersion,
            @Parameter(hidden = true) @AuthenticationPrincipal(errorOnInvalidType = true) Long userId,
            @Parameter(description = "수정할 타임라인 이벤트 ID") @PathVariable Long timelineEventId,
            @RequestBody UpdateTimelineEventRequest request);

    @Operation(summary = "타임라인 Event 메모 작성·수정·제거",
            description = "메모를 요청 값으로 교체하는 단일 endpoint다. memo가 null·공백뿐이거나 필드가 없으면(`{}`) "
                    + "메모를 제거한다. 그 외 문자열은 trim 없이 원문 그대로 저장한다(String.length() 기준 최대 500자).")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "반영 성공(body=null)", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "`-400` — memo가 500자 초과"),
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
                    description = "`-1003` — 이벤트가 속한 하루 기록이 이미 SAVED(작성완료)")
    })
    @DeleteMapping("/events/{timelineEventId}")
    ResponseEntity<ApiResponse<Void>> deleteTimelineEvent(
            @Parameter(description = "API 버전", example = "v1") @PathVariable String applicationVersion,
            @Parameter(hidden = true) @AuthenticationPrincipal(errorOnInvalidType = true) Long userId,
            @Parameter(description = "삭제할 타임라인 이벤트 ID") @PathVariable Long timelineEventId);

    @Operation(summary = "타임라인 Event에서 사진(PHOTO Item) 삭제",
            description = "Event와 PHOTO Item의 연결(junction)만 해제한다 — 사진 자체 삭제가 아니라서 같은 "
                    + "사진이 다른 Event에도 연결돼 있으면 그쪽에는 그대로 남는다. 마지막 Event 참조가 사라지는 "
                    + "PHOTO Item은 S3 삭제 작업과 함께 보존하며, commit 뒤 별도 worker가 S3 성공 시 Item과 "
                    + "작업을 최종 삭제한다. 따라서 200은 연결 해제와 PHOTO 정리 작업 등록 성공을 뜻하고 S3 삭제 "
                    + "완료를 기다리지 않는다. 현재 정책상 PHOTO Item만 해제할 수 있다(제한은 URL이 아니라 서버 "
                    + "정책 — non-PHOTO는 400). Event의 마지막 Item을 해제해도 Event는 유지된다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "연결 해제 성공(body 없음)", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "`-1018` — 대상 Item이 PHOTO가 아님(연결된 Item에만 해당 — 미연결은 404)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "`-2001` — 인증 필요(Bearer access token 부재/무효/만료)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "`-404` — 이벤트가 없거나 내 소유가 아니거나, Item이 없거나 해당 이벤트에 "
                            + "연결돼 있지 않음(존재 여부는 구분해 주지 않는다)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "`-1003` — 이벤트가 속한 하루 기록이 이미 SAVED(작성완료)")
    })
    @DeleteMapping("/events/{timelineEventId}/items/{timelineItemId}")
    ResponseEntity<ApiResponse<Void>> detachTimelineEventItem(
            @Parameter(description = "API 버전", example = "v1") @PathVariable String applicationVersion,
            @Parameter(hidden = true) @AuthenticationPrincipal(errorOnInvalidType = true) Long userId,
            @Parameter(description = "연결을 해제할 타임라인 이벤트 ID") @PathVariable Long timelineEventId,
            @Parameter(description = "연결을 해제할 타임라인 아이템 ID") @PathVariable Long timelineItemId);

    @Operation(summary = "하루 기록(DailyRecord) 삭제(ID, deprecated)", deprecated = true,
            description = "호환을 위해 한시적으로 유지하는 ID 기반 삭제다. 신규 클라이언트는 "
                    + "DELETE /daily-records/{recordDate}를 사용한다. 하루 전체 Record·Events와 마지막 "
                    + "참조가 사라지는 non-PHOTO Items를 DB에서 삭제한다. "
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
                    description = "`-1003` — 하루 기록이 이미 SAVED(작성완료)")
    })
    @DeleteMapping("/daily-records/by-id/{dailyRecordId}")
    ResponseEntity<ApiResponse<Void>> deleteDailyRecord(
            @Parameter(description = "API 버전", example = "v1") @PathVariable String applicationVersion,
            @Parameter(hidden = true) @AuthenticationPrincipal(errorOnInvalidType = true) Long userId,
            @Parameter(description = "삭제할 하루 기록 ID") @PathVariable Long dailyRecordId);

    @Operation(summary = "하루 기록(DailyRecord) 날짜 삭제",
            description = "인증 사용자가 선택한 날짜의 DRAFT 하루 기록을 삭제한다. recordDate는 yyyy-MM-dd "
                    + "형식이며 서버에서 계산·timezone 보정하지 않는다. 하루 전체 Record·Events와 마지막 참조가 "
                    + "사라지는 non-PHOTO Items를 DB에서 삭제한다. 마지막 참조가 사라지는 PHOTO Item은 S3 삭제 "
                    + "작업과 함께 보존하며, commit 뒤 별도 worker가 S3 성공 시 Item과 작업을 최종 삭제한다. "
                    + "따라서 200은 root 삭제와 PHOTO 정리 작업 등록 성공을 뜻한다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "삭제 성공(body 없음)", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "`-400` — recordDate가 올바른 ISO 날짜 형식이 아님"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "`-2001` — 인증 필요(Bearer access token 부재/무효/만료)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "`-404` — 해당 날짜의 내 하루 기록이 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "`-1003` — 하루 기록이 이미 SAVED(작성완료)")
    })
    @DeleteMapping("/daily-records/{recordDate}")
    ResponseEntity<ApiResponse<Void>> deleteDailyRecordByDate(
            @Parameter(description = "API 버전", example = "v1") @PathVariable String applicationVersion,
            @Parameter(hidden = true) @AuthenticationPrincipal(errorOnInvalidType = true) Long userId,
            @Parameter(description = "삭제할 기록 날짜", example = "2026-07-08")
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate recordDate);

    @Operation(summary = "하루 기록(DailyRecord) 저장(작성완료)",
            description = "인증 사용자가 선택한 날짜의 DRAFT 하루 기록을 SAVED로 확정한다. request body는 없다. "
                    + "**200이 곧 저장 완료다** — 전이가 커밋된 뒤 응답한다. 저장 후에는 Event 수정·메모·삭제, "
                    + "Item 연결 해제, 같은 날짜 draft 추가가 모두 `-1003`으로 거절된다. "
                    + "커밋 뒤 서버가 User Memory 갱신을 별도로 진행하지만 그 성패는 이 응답과 무관하며 "
                    + "클라이언트가 기다리거나 조회할 대상이 아니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "저장 성공(body=null)", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "`-400` — recordDate가 올바른 ISO 날짜 형식이 아님"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "`-2001` — 인증 필요(Bearer access token 부재/무효/만료)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "`-404` — 해당 날짜의 내 하루 기록이 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "`-1003` — 하루 기록이 이미 SAVED(작성완료). 응답 유실 뒤 재시도한 "
                            + "클라이언트에게는 \"앞선 저장이 성공했다\"는 뜻이다")
    })
    @PostMapping("/daily-records/{recordDate}/save")
    ResponseEntity<ApiResponse<Void>> saveDailyRecord(
            @Parameter(description = "API 버전", example = "v1") @PathVariable String applicationVersion,
            @Parameter(hidden = true) @AuthenticationPrincipal(errorOnInvalidType = true) Long userId,
            @Parameter(description = "저장할 기록 날짜", example = "2026-07-08")
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate recordDate);
}
