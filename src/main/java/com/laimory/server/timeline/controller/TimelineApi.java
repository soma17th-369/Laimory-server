package com.laimory.server.timeline.controller;

import com.laimory.server.common.ApiResponse;
import com.laimory.server.common.ApiUrls;
import com.laimory.server.terms.RequiredTermsStage;
import com.laimory.server.terms.TermStage;
import com.laimory.server.user.CurrentSubject;
import com.laimory.server.timeline.dto.CreateDraftTaskRequest;
import com.laimory.server.timeline.dto.CreateDraftTaskResponse;
import com.laimory.server.timeline.dto.DraftTaskListResponse;
import com.laimory.server.timeline.dto.DraftTaskStatusResponse;
import com.laimory.server.timeline.dto.PhotoUploadCreateRequest;
import com.laimory.server.timeline.dto.PhotoUploadCreateResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 타임라인 draft 작성 작업 API의 문서·계약(구현은 {@link TimelineController}). 콜백은 서버간 통신이라 {@link TimelineCallbackController}에 분리.
 *
 * <p>모든 엔드포인트가 사용자 콘텐츠에 종속된 작업이라 인증 prefix({@code /a/api})에 둔다(사진 presign은
 * S3 객체를 만들어내므로 공개 노출 시 남발/비용 위험 — 인증 경계로 보호). JWT raw userId는
 * {@link CurrentSubject}로 subjectId에 해석하며 클라이언트 입력(query/body/path)이 아니다.
 *
 * <p>버전은 {@code @PathVariable applicationVersion}으로 받아 그대로 Service에 넘긴다 — 버전별 분기는 Service 책임.
 */
@Tag(name = "Timeline Draft", description = "타임라인 draft 작성 작업 — 생성·폴링·사진 업로드 URL 발급")
@SecurityRequirement(name = "bearerAuth")
@RequestMapping(ApiUrls.AUTHENTICATED_API_URL + "/timeline/drafts")
public interface TimelineApi {

    /**
     * createDraftTask 요청 바디 예시. payload는 itemType별로 형태가 다른데(oneOf), Swagger의 자동 예시는
     * itemType↔payload를 못 맞춰 엉뚱한 조합을 보여주므로, 6개 itemType의 올바른 payload 짝을 모두 담은
     * 예시를 명시해 request body에 그대로 노출한다. 서버 주입 read-only 필드(photoUrl·address·places·durationText)는
     * {@code null}로 함께 보여 "요청 시 채우지 않아도 됨(서버가 주입)"을 알린다(스키마엔 read-only로 표시).
     *
     * <p>예시는 의도적으로 "다음날 아침에 쓰는 어제 일기" 시나리오다 — recordDate(07-08)와 recordAt(07-09 아침)의
     * 날짜가 다른 것이 정상 계약임을 예시 자체가 보여준다(서버는 정합성을 검증하지 않는다).
     */
    String CREATE_DRAFT_EXAMPLE = """
            {
              "recordDate": "2026-07-08",
              "recordAt": "2026-07-09T09:12:34",
              "recordTimeZone": "Asia/Seoul",
              "timelineWindow": {
                "startTime": "2026-07-08T00:00",
                "endTime": "2026-07-09T00:00"
              },
              "sourceItems": [
                {
                  "itemType": "PHOTO",
                  "rawId": "0190a1b2-0001-7000-8000-000000000001",
                  "startAt": "2026-07-08T09:05:00",
                  "endAt": "2026-07-08T09:05:00",
                  "payload": {
                    "filename": "0190a1b2.jpg",
                    "clientPhotoUri": "content://media/external/images/media/1001",
                    "latitude": 37.5665,
                    "longitude": 126.9780,
                    "description": "카페에서 찍은 사진",
                    "photoUrl": null
                  }
                },
                {
                  "itemType": "CALENDAR",
                  "rawId": "0190a1b2-0002-7000-8000-000000000002",
                  "startAt": "2026-07-08T10:00:00",
                  "endAt": "2026-07-08T11:00:00",
                  "payload": {
                    "title": "팀 미팅",
                    "locationText": "본사 3층 회의실",
                    "description": "주간 스프린트 리뷰",
                    "allDay": false
                  }
                },
                {
                  "itemType": "STAY",
                  "rawId": "0190a1b2-0003-7000-8000-000000000003",
                  "startAt": "2026-07-08T12:00:00",
                  "endAt": "2026-07-08T13:00:00",
                  "payload": {
                    "latitude": 37.5013,
                    "longitude": 127.0396,
                    "address": null,
                    "places": null,
                    "durationText": null
                  }
                },
                {
                  "itemType": "MOVEMENT",
                  "rawId": "0190a1b2-0004-7000-8000-000000000004",
                  "startAt": "2026-07-08T13:00:00",
                  "endAt": "2026-07-08T13:30:00",
                  "payload": {
                    "start": { "latitude": 37.5013, "longitude": 127.0396, "address": null, "places": null },
                    "end": { "latitude": 37.5172, "longitude": 127.0473, "address": null, "places": null },
                    "transports": "WALKING",
                    "distanceMeters": 1200.0
                  }
                },
                {
                  "itemType": "HEALTH",
                  "rawId": "0190a1b2-0005-7000-8000-000000000005",
                  "startAt": "2026-07-08T00:00:00",
                  "endAt": "2026-07-08T23:59:59",
                  "payload": {
                    "metric": "STEPS",
                    "value": "8500보"
                  }
                },
                {
                  "itemType": "NOTIFICATION",
                  "rawId": "0190a1b2-0006-7000-8000-000000000006",
                  "startAt": "2026-07-08T14:00:00",
                  "endAt": "2026-07-08T14:00:00",
                  "payload": {
                    "appName": "카카오톡",
                    "title": "새 메시지",
                    "text": "안녕하세요!"
                  }
                }
              ]
            }
            """;

    @Operation(summary = "draft 작업 생성",
            description = "sourceItems(하루 기록 원천: 머문 곳·이동·사진·걸음 수·알림 등)를 받아 AI 타임라인 생성 작업을 시작한다. "
                    + "AI 접수가 확인된 경우에만 202와 taskId를 반환하며, taskId를 `GET /{taskId}`로 폴링해 결과를 조회한다. "
                    + "AI 접수 실패는 502(-1009)이고 이때 응답에 taskId는 없다. "
                    + "각 sourceItem의 startAt은 필수이고 endAt은 nullable이다. "
                    + "HEALTH payload의 metric은 STEPS만 허용하며 다른 값은 저장·AI dispatch 전에 400으로 거절한다. "
                    + "STAY·MOVEMENT·좌표가 있는 PHOTO를 신규 처리하면 현재 위치약관 동의가 필요하며, "
                    + "미동의 시 위치 항목을 제외해 재시도할 수 있다. 서버가 위치를 자동 제거하지 않는다. "
                    + "지오코딩 대상 고유 좌표(rawId 중복 제거·이미 저장된 item 제외 뒤 STAY 좌표와 MOVEMENT "
                    + "start/end를 dedupe한 수)는 최대 30개다 — sourceItems 배열 길이 제한이 아니라 파생 계산이며, "
                    + "초과하면 외부 지도 API 호출 없이 400(-400)으로 거절한다. "
                    + "payload의 photoUrl·address·places·durationText는 서버가 채우는 read-only 값이라 요청에선 null/생략한다(스키마에 read-only 표시). "
                    + "지오코딩이 일부 실패해도 품질 기준(고유 좌표 실패 20% 이하이고 시간순 연속 실패 3개 미만) 안이면 "
                    + "작업은 계속되고, 실패 좌표는 address 없이(places는 빈 배열) 저장된다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202",
                    description = "작업 접수 — body.taskId로 폴링", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "`-400` — 필수값 누락·불량 입력(recordDate/recordAt/recordTimeZone/"
                            + "timelineWindow/sourceItems 등, 각 sourceItem의 startAt 누락, "
                            + "HEALTH의 STEPS 외 metric, window의 `startTime >= endTime`, "
                            + "지오코딩 대상 고유 좌표 30개 초과 포함). "
                            + "recordDate가 `1000-01-01`~`9999-12-31` 범위 밖이거나 `recordTimeZone` 기준 "
                            + "오늘보다 미래이면 record 조회·지오코딩·저장·AI dispatch 전에 거절한다"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "`-2001` — 인증 필요(Bearer access token 부재/무효/만료)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "`-3001` — 현재 필수 약관 미동의(LOGIN 또는 TIMELINE_FIRST_CREATE 단계), "
                            + "또는 위치정보를 포함한 요청의 현재 위치약관 미동의. 현재 약관에 동의하거나 "
                            + "STAY·MOVEMENT·좌표가 있는 PHOTO를 제외한 뒤 재시도한다"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "`-1003`(해당 날짜의 하루 기록이 이미 SAVED) · "
                            + "`-1013`(요청의 모든 item이 이미 타임라인에 저장됨 — 추가할 신규 없음)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "502",
                    description = "`-1009`(AI 접수 실패 — AI가 거절했거나 접수 확인 불가. 응답에 taskId 없음) · "
                            + "지오코딩 품질 기준 미달(고유 좌표 실패 20% 초과 또는 시간순 연속 실패 3개)이면 "
                            + "재시도 가능성으로 코드가 나뉜다 — "
                            + "`-1014`(전이적 실패 — 잠시 뒤 재시도로 해결될 수 있음) · "
                            + "`-1015`(영구 실패 포함 — 쿼터·키·응답 오류, 즉시 재시도는 무의미)")
    })
    @PostMapping
    @RequiredTermsStage(TermStage.TIMELINE_FIRST_CREATE)
    ResponseEntity<ApiResponse<CreateDraftTaskResponse>> createDraftTask(
            @Parameter(description = "API 버전", example = "v1") @PathVariable String applicationVersion,
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId,
            @Parameter(hidden = true) @CurrentSubject UUID subjectId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    content = @Content(schema = @Schema(implementation = CreateDraftTaskRequest.class),
                            examples = @ExampleObject(name = "6개 itemType 전체 예시", value = CREATE_DRAFT_EXAMPLE)))
            @RequestBody CreateDraftTaskRequest request);

    @Operation(summary = "사진 업로드 URL 발급",
            description = "업로드할 사진 목록(contentType·size)을 받아 S3 presigned PUT URL을 발급한다. "
                    + "클라이언트는 발급된 URL로 사진 바이너리를 직접 PUT 업로드한다(URL 유효시간 내).")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "발급 성공", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "`-1004`(최대 장수 초과) · `-1005`(장당 크기 초과) · "
                            + "`-1007`(미지원 포맷 — JPG/PNG/WebP만) · `-400`(필수값 누락)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "`-2001` — 인증 필요(Bearer access token 부재/무효/만료)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "`-3001` — 현재 필수 약관 미동의(LOGIN 또는 TIMELINE_FIRST_CREATE 단계). "
                            + "presign은 S3 객체 수집의 시작점이라 동의 전에 발급하지 않는다")
    })
    @PostMapping("/photo-uploads")
    @RequiredTermsStage(TermStage.TIMELINE_FIRST_CREATE)
    ResponseEntity<ApiResponse<PhotoUploadCreateResponse>> createPhotoUploads(
            @Parameter(description = "API 버전", example = "v1") @PathVariable String applicationVersion,
            @Parameter(hidden = true) @CurrentSubject UUID subjectId,
            @RequestBody PhotoUploadCreateRequest request);

    @Operation(summary = "진행 중 draft 작업 목록 조회",
            description = "인증 사용자가 소유한 현재 진행 중(PROCESSING) draft 작업의 taskId만 생성 최신순으로 반환한다. "
                    + "앱 재진입 등으로 taskId를 잃은 클라이언트가 `GET /{taskId}` 폴링으로 복귀하는 재발견 용도다. "
                    + "완료·실패·만료된 작업과 다른 사용자의 작업은 포함하지 않으며, 진행 작업이 없으면 "
                    + "taskIds 빈 배열을 반환한다(404 아님). 목록 반환 직후에도 작업은 종결·만료될 수 있으므로 "
                    + "각 taskId의 최신 상태는 단건 폴링이 권위다(폴링의 404·FAILED는 정상 수명주기로 처리).")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "조회 성공 — 진행 작업이 없으면 body.taskIds는 빈 배열", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "`-2001` — 인증 필요(Bearer access token 부재/무효/만료)")
    })
    @GetMapping
    ResponseEntity<ApiResponse<DraftTaskListResponse>> listProcessingDraftTasks(
            @Parameter(description = "API 버전", example = "v1") @PathVariable String applicationVersion,
            @Parameter(hidden = true) @CurrentSubject UUID subjectId);

    @Operation(summary = "draft 작업 상태 폴링",
            description = "PROCESSING이면 status와 elapsedSeconds, SUCCESS면 result(그날 타임라인 — "
                    + "DailyRecord status(DRAFT/SAVED) 포함), FAILED면 body.error에 실패 분류 코드가 담긴다"
                    + "(FAILED도 HTTP 200 + 0). body.error 코드: `-1008`(AI가 실패 보고) · "
                    + "`-1009`(AI 요청 전달 실패) · `-1011`(서버 처리 실패). "
                    + "미지의 코드는 제네릭 실패로 처리한다(전방 호환). "
                    + "PROCESSING에는 `body.elapsedSeconds`(AI 작업 대기 경과 시간, 완료된 초·0 이상)가 함께 담긴다 — "
                    + "기준은 서버가 전처리를 마치고 AI dispatch 대기 단계에 들어간 시각이다(POST 접수 시각 아님). "
                    + "SUCCESS/FAILED에서는 필드가 생략되므로 optional로 파싱한다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "폴링 성공(작업 상태는 body.status)", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "`-2001` — 인증 필요(Bearer access token 부재/무효/만료)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "`-1001`(작업 없음 — 만료·타인 작업 포함, 존재 여부는 구분해 주지 않는다) · "
                            + "`-404`(SUCCESS 작업의 결과 기록이 삭제됐거나 소유자가 일치하지 않는 경우 — "
                            + "클라이언트는 1001=작업 소멸, 0404=결과 소멸로 구분)")
    })
    @GetMapping("/{taskId}")
    ResponseEntity<ApiResponse<DraftTaskStatusResponse>> pollDraftTask(
            @Parameter(description = "API 버전", example = "v1") @PathVariable String applicationVersion,
            @Parameter(hidden = true) @CurrentSubject UUID subjectId,
            @Parameter(description = "draft 작업 ID (생성 응답의 taskId)") @PathVariable String taskId);
}
