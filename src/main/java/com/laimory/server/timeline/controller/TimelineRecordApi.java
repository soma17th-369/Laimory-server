package com.laimory.server.timeline.controller;

import com.laimory.server.common.ApiResponse;
import com.laimory.server.common.ApiUrls;
import com.laimory.server.user.CurrentSubject;
import com.laimory.server.timeline.dto.CreateTimelineEventRequest;
import com.laimory.server.timeline.dto.DailyTimelineResponse;
import com.laimory.server.timeline.dto.DailyTimelinesResponse;
import com.laimory.server.timeline.dto.MonthlyDailyRecordListResponse;
import com.laimory.server.timeline.dto.SaveDailyRecordRequest;
import com.laimory.server.timeline.dto.TimelineEventResponse;
import com.laimory.server.timeline.dto.UpdateDailyRecordEmotionRequest;
import com.laimory.server.timeline.dto.UpdateTimelineEventMemoRequest;
import com.laimory.server.timeline.dto.UpdateTimelineEventRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 확정 타임라인 기록 조회·편집 API의 문서·계약(구현은 {@link TimelineRecordController}).
 * draft 작성 작업(생성·폴링)은 {@link TimelineApi}에 분리 — 여기는 finalize로 만들어진 기록을 다룬다.
 *
 * <p>모든 엔드포인트가 특정 사용자의 기록에 종속되므로 인증 prefix({@code /a/api})에 둔다.
 * JWT raw userId는 {@link CurrentSubject}로 subjectId에 해석하며 클라이언트 입력이 아니다 — OpenAPI
 * parameter로 노출하지 않는다.
 *
 * <p>편집·삭제는 하루 기록 상태와 무관하게 허용한다(SAVED 포함). AI 작업 진행(PROCESSING)만으로
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
                    + "각 기록의 status(DRAFT/SAVED, non-null)가 함께 반환되고, "
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
            @Parameter(hidden = true) @CurrentSubject UUID subjectId);

    @Operation(summary = "하루 타임라인 단건 조회(ID, deprecated)", deprecated = true,
            description = "호환을 위해 한시적으로 유지하는 ID 기반 조회다. 신규 클라이언트는 "
                    + "GET /daily-records/{recordDate}를 사용한다. 인증 사용자가 소유한 하루 기록과 "
                    + "Event·Item graph를 status(DRAFT/SAVED)와 함께 반환하며, "
                    + "기록이 없거나 다른 사용자 소유이면 404로 응답한다.")
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
            @Parameter(hidden = true) @CurrentSubject UUID subjectId,
            @Parameter(description = "조회할 하루 기록 ID") @PathVariable Long dailyRecordId);

    @Operation(summary = "하루 타임라인 날짜 조회",
            description = "인증 사용자가 선택한 날짜의 하루 기록과 Event·Item graph를 status(DRAFT/SAVED)와 "
                    + "함께 반환한다. recordDate는 "
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
            @Parameter(hidden = true) @CurrentSubject UUID subjectId,
            @Parameter(description = "조회할 기록 날짜", example = "2026-07-08")
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate recordDate);

    @Operation(summary = "캘린더 월별 하루 기록 경량 조회",
            description = "인증 사용자가 소유한 해당 월(양끝 포함)의 DRAFT/SAVED 하루 기록을 recordDate "
                    + "오름차순으로 반환한다. 앱 캘린더 화면용 경량 read model이라 각 항목은 recordDate와 "
                    + "nullable emotionType만 담고 Event·Item graph는 조회하지 않는다. "
                    + "기록이 없는 월은 404가 아니라 dailyRecords 빈 배열이다. "
                    + "year는 1000~9999(MySQL DATE 지원 범위), month는 1~12만 허용한다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "월별 조회 성공 — 기록이 없으면 dailyRecords 빈 배열", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "`-400` — year/month 누락·정수 아님·범위 밖(year 1000~9999, month 1~12)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "`-2001` — 인증 필요(Bearer access token 부재/무효/만료)")
    })
    @GetMapping("/monthly-records")
    ResponseEntity<ApiResponse<MonthlyDailyRecordListResponse>> getMonthlyDailyRecords(
            @Parameter(description = "API 버전", example = "v1") @PathVariable String applicationVersion,
            @Parameter(hidden = true) @CurrentSubject UUID subjectId,
            @Parameter(description = "조회할 연도(1000~9999)", example = "2026") @RequestParam int year,
            @Parameter(description = "조회할 월(1~12)", example = "5") @RequestParam int month);

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
            @Parameter(hidden = true) @CurrentSubject UUID subjectId,
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
                    + "같은 rawId 재시도는 저장된 시간·클라이언트 입력 payload가 모두 같을 때만 no-op 또는 "
                    + "같은 record PHOTO 재사용으로 수렴하며, 다르면 400이다. "
                    + "시간은 보낸 값 그대로 저장한다 — draft 생성의 +10분 충돌 보정은 없다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "수정 성공(body=null)", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "`-400` — 4개 키 중 누락 · title null/공백·255자 초과 · subtitle 255자 초과 · "
                            + "startAt null · endAt이 startAt보다 이전 · eventType 명시적 null/미지원 literal · "
                            + "memo 500자 초과 · photosToAdd null/PHOTO 입력 오류/rawId·filename 충돌·"
                            + "기존 PHOTO 입력 불일치 · "
                            + "`-1004` — 사진 수 초과"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "`-2001` — 인증 필요(Bearer access token 부재/무효/만료)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "`-404` — 이벤트가 없거나 내 소유가 아님(존재 여부는 구분해 주지 않는다)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "`-1019` — 같은 PHOTO object를 삭제하는 중이므로 잠시 후 새 업로드 필요")
    })
    @PatchMapping("/events/{timelineEventId}")
    ResponseEntity<ApiResponse<Void>> updateTimelineEvent(
            @Parameter(description = "API 버전", example = "v1") @PathVariable String applicationVersion,
            @Parameter(hidden = true) @CurrentSubject UUID subjectId,
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
                    description = "`-404` — 이벤트가 없거나 내 소유가 아님(존재 여부는 구분해 주지 않는다)")
    })
    @PutMapping("/events/{timelineEventId}/memo")
    ResponseEntity<ApiResponse<Void>> updateTimelineEventMemo(
            @Parameter(description = "API 버전", example = "v1") @PathVariable String applicationVersion,
            @Parameter(hidden = true) @CurrentSubject UUID subjectId,
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
                    description = "`-404` — 이벤트가 없거나 내 소유가 아님(존재 여부는 구분해 주지 않는다)")
    })
    @DeleteMapping("/events/{timelineEventId}")
    ResponseEntity<ApiResponse<Void>> deleteTimelineEvent(
            @Parameter(description = "API 버전", example = "v1") @PathVariable String applicationVersion,
            @Parameter(hidden = true) @CurrentSubject UUID subjectId,
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
                            + "연결돼 있지 않음(존재 여부는 구분해 주지 않는다)")
    })
    @DeleteMapping("/events/{timelineEventId}/items/{timelineItemId}")
    ResponseEntity<ApiResponse<Void>> detachTimelineEventItem(
            @Parameter(description = "API 버전", example = "v1") @PathVariable String applicationVersion,
            @Parameter(hidden = true) @CurrentSubject UUID subjectId,
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
                    description = "`-404` — 하루 기록이 없거나 내 소유가 아님(존재 여부는 구분해 주지 않는다)")
    })
    @DeleteMapping("/daily-records/by-id/{dailyRecordId}")
    ResponseEntity<ApiResponse<Void>> deleteDailyRecord(
            @Parameter(description = "API 버전", example = "v1") @PathVariable String applicationVersion,
            @Parameter(hidden = true) @CurrentSubject UUID subjectId,
            @Parameter(description = "삭제할 하루 기록 ID") @PathVariable Long dailyRecordId);

    @Operation(summary = "하루 기록(DailyRecord) 날짜 삭제",
            description = "인증 사용자가 선택한 날짜의 하루 기록을 삭제한다(DRAFT/SAVED 모두). recordDate는 yyyy-MM-dd "
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
                    description = "`-404` — 해당 날짜의 내 하루 기록이 없음")
    })
    @DeleteMapping("/daily-records/{recordDate}")
    ResponseEntity<ApiResponse<Void>> deleteDailyRecordByDate(
            @Parameter(description = "API 버전", example = "v1") @PathVariable String applicationVersion,
            @Parameter(hidden = true) @CurrentSubject UUID subjectId,
            @Parameter(description = "삭제할 기록 날짜", example = "2026-07-08")
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate recordDate);

    @Operation(summary = "하루 기록(DailyRecord) 저장(작성완료)",
            description = "인증 사용자가 선택한 날짜의 DRAFT 하루 기록을 SAVED로 확정하면서 하루 감정을 함께 "
                    + "저장한다. request body의 `emotionType`은 필수다(VERY_HAPPY·HAPPY·NEUTRAL·UNHAPPY·"
                    + "VERY_UNHAPPY). **200이 곧 저장 완료다** — 감정과 SAVED 전이가 한 트랜잭션으로 커밋된 뒤 "
                    + "응답한다. 저장 후에도 Event 수정·메모·삭제·Item 연결 해제는 계속 허용되며, "
                    + "같은 날짜 draft 추가만 `-1003`으로 거절된다. "
                    + "커밋 뒤 서버가 User Memory 갱신을 별도로 진행하지만 그 성패는 이 응답과 무관하며 "
                    + "클라이언트가 기다리거나 조회할 대상이 아니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "저장 성공(body=null)", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "`-400` — recordDate가 올바른 ISO 날짜 형식이 아님 · body 없음(zero-byte, "
                            + "Content-Type 유무 무관) · emotionType 누락/null/미지원 값 · 깨진 JSON"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "`-2001` — 인증 필요(Bearer access token 부재/무효/만료)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "`-404` — 해당 날짜의 내 하루 기록이 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "`-1003` — 하루 기록이 이미 SAVED(작성완료). 응답 유실 뒤 재시도한 "
                            + "클라이언트에게는 \"앞선 저장이 성공했다\"는 뜻이다"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "415",
                    description = "`-415` — body는 있는데 Content-Type이 없거나 JSON이 아님")
    })
    @PostMapping("/daily-records/{recordDate}/save")
    ResponseEntity<ApiResponse<Void>> saveDailyRecord(
            @Parameter(description = "API 버전", example = "v1") @PathVariable String applicationVersion,
            @Parameter(hidden = true) @CurrentSubject UUID subjectId,
            @Parameter(description = "저장할 기록 날짜", example = "2026-07-08")
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate recordDate,
            @RequestBody SaveDailyRecordRequest request);

    @Operation(summary = "저장 완료 하루 기록의 감정 수정",
            description = "인증 사용자가 선택한 날짜의 SAVED 하루 기록의 확정 감정을 요청 값으로 교체한다. "
                    + "recordDate는 yyyy-MM-dd 형식이며 서버에서 계산·timezone 보정하지 않는다. "
                    + "request body의 `emotionType`은 필수다(VERY_HAPPY·HAPPY·NEUTRAL·UNHAPPY·VERY_UNHAPPY). "
                    + "대상은 SAVED 기록뿐이다 — DRAFT의 최초 감정 확정은 기존 저장 API"
                    + "(POST /daily-records/{recordDate}/save)가 담당하며, DRAFT에 요청하면 `-1020`으로 "
                    + "거절된다. 같은 값 재요청도 멱등 성공이고 동시 수정은 마지막으로 커밋된 값이 남는다. "
                    + "저장 API와 달리 이 수정은 User Memory 갱신을 새로 등록하지 않는다"
                    + "(SAVED 후 편집과 같은 정책).")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "수정 성공(body=null)", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "`-400` — recordDate가 올바른 ISO 날짜 형식이 아님 · body 없음(zero-byte, "
                            + "Content-Type 유무 무관) · emotionType 누락/null/미지원 값/숫자 등 비문자열 · "
                            + "깨진 JSON"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "`-2001` — 인증 필요(Bearer access token 부재/무효/만료)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "`-404` — 해당 날짜의 내 하루 기록이 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "`-1020` — 하루 기록이 아직 DRAFT라 수정할 확정 감정이 없음. "
                            + "감정은 저장 API로 처음 확정한다"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "415",
                    description = "`-415` — body는 있는데 Content-Type이 없거나 JSON이 아님")
    })
    @PutMapping("/daily-records/{recordDate}/emotion")
    ResponseEntity<ApiResponse<Void>> updateDailyRecordEmotion(
            @Parameter(description = "API 버전", example = "v1") @PathVariable String applicationVersion,
            @Parameter(hidden = true) @CurrentSubject UUID subjectId,
            @Parameter(description = "감정을 수정할 기록 날짜", example = "2026-07-08")
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate recordDate,
            @RequestBody UpdateDailyRecordEmotionRequest request);

    @Operation(summary = "하루 기록에 타임라인 Event 수동 생성",
            description = "인증 사용자가 선택한 날짜의 기존 하루 기록에 Event를 하나 생성한다(DRAFT/SAVED 모두). "
                    + "recordDate는 yyyy-MM-dd 형식이며 서버에서 계산·timezone 보정하지 않는다. "
                    + "하루 기록 자체를 자동 생성하지 않는다 — 해당 날짜 기록이 없으면 404다. "
                    + "eventType·title·subtitle·startAt·endAt 5개 키를 모두 보내는 계약이다: 키가 하나라도 "
                    + "없으면 400이다. eventType·title·startAt의 null은 400, subtitle·endAt은 값이 nullable이다. "
                    + "memo는 optional 키다(누락/null/공백뿐은 메모 없음, 그 외 trim 없이 원문 최대 500자). "
                    + "photosToAdd도 optional 키다 — 누락/빈 배열은 사진 없음, 명시적 null은 400이며, "
                    + "검증·중복·개수·기존 PHOTO 재사용·재시도 수렴 규칙은 기존 Event PATCH photosToAdd와 "
                    + "동일하다. 같은 rawId의 기존 PHOTO는 저장된 시간·클라이언트 입력 payload가 모두 같을 "
                    + "때만 재사용하고, 다르면 400이다. 클라이언트가 presign·S3 업로드 성공을 확인한 뒤 호출해야 하며 서버는 S3 존재 "
                    + "여부를 확인하지 않고, description은 저장하지 않으며 photoUrl은 인증 사용자와 filename으로 "
                    + "생성한다. Event·PHOTO Item·연결은 한 트랜잭션으로 커밋된다 — 일부 실패 시 사진 없는 "
                    + "Event만 남지 않는다. 필드 규칙은 기존 Event PATCH와 같고, 시간은 보낸 값 그대로 "
                    + "저장한다 — AI 결과 저장의 +10분 충돌 보정은 없다. 수동 Event의 question·place·address는 "
                    + "null이고, 함께 연결된 PHOTO Item은 응답 items에 조회와 같은 정렬로 포함된다(사진 없으면 "
                    + "빈 목록). 생성 후 사진 추가는 기존 Event PATCH photosToAdd도 계속 지원한다. "
                    + "이 생성은 User Memory 갱신을 새로 등록하지 않는다(SAVED 후 편집과 같은 정책).")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "생성 성공 — 생성된 timelineEventId와 Event 표현"
                            + "(question/place/address=null, 연결 PHOTO Item 포함 items) 반환",
                    useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "`-400` — recordDate가 올바른 ISO 날짜 형식이 아님 · body 없음·깨진 JSON · "
                            + "5개 키 중 누락 · eventType null/미지원 literal/숫자 등 비문자열 · "
                            + "title null/공백·255자 초과 · subtitle 255자 초과 · "
                            + "startAt null/시간 포맷 오류 · endAt이 startAt보다 이전 · memo 500자 초과 · "
                            + "photosToAdd null/PHOTO 입력 오류/rawId·filename 충돌·기존 PHOTO 입력 불일치 · "
                            + "`-1004` — 사진 수 초과"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "`-2001` — 인증 필요(Bearer access token 부재/무효/만료)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "`-404` — 해당 날짜의 내 하루 기록이 없음(존재 여부는 구분해 주지 않는다)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "`-1019` — 같은 PHOTO object를 삭제하는 중이므로 잠시 후 새 업로드 필요"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "415",
                    description = "`-415` — body는 있는데 Content-Type이 없거나 JSON이 아님")
    })
    @PostMapping("/daily-records/{recordDate}/events")
    ResponseEntity<ApiResponse<TimelineEventResponse>> createTimelineEvent(
            @Parameter(description = "API 버전", example = "v1") @PathVariable String applicationVersion,
            @Parameter(hidden = true) @CurrentSubject UUID subjectId,
            @Parameter(description = "Event를 생성할 기록 날짜", example = "2026-07-08")
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate recordDate,
            @RequestBody CreateTimelineEventRequest request);
}
