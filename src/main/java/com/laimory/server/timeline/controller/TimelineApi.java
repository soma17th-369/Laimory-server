package com.laimory.server.timeline.controller;

import com.laimory.server.common.ApiResponse;
import com.laimory.server.common.ApiUrls;
import com.laimory.server.timeline.dto.CreateDraftTaskRequest;
import com.laimory.server.timeline.dto.CreateDraftTaskResponse;
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
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 타임라인 draft 작성 작업 API의 문서·계약(구현은 {@link TimelineController}). 콜백은 서버간 통신이라 {@link TimelineCallbackController}에 분리.
 *
 * <p>모든 엔드포인트가 userId에 종속된 작업이라 인증 prefix({@code /a/api})에 둔다(사진 presign은 S3 객체를
 * 만들어내므로 공개 노출 시 남발/비용 위험 — 인증 경계로 보호). 사용자 인증 도입 전까지는 {@code TimelineDefaults}의
 * 고정 userId를 쓰지만 경로는 인증 prefix로 고정한다.
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
     */
    String CREATE_DRAFT_EXAMPLE = """
            {
              "recordAt": "2026-07-08T09:00:00",
              "recordTimeZone": "Asia/Seoul",
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
            description = "sourceItems(하루 기록 원천: 머문 곳·이동·사진·건강·알림 등)를 받아 AI 타임라인 생성 작업을 시작한다. "
                    + "202로 반환된 taskId를 `GET /{taskId}`로 폴링해 결과를 조회한다. "
                    + "payload의 photoUrl·address·places·durationText는 서버가 채우는 read-only 값이라 요청에선 null/생략한다(스키마에 read-only 표시).")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202",
                    description = "작업 접수 — body.taskId로 폴링", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "`ERROR_0400` — 필수값 누락·불량 입력(recordAt/recordTimeZone/sourceItems 등)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "`ERROR_1003`(해당 날짜의 하루 기록이 이미 SAVED) · "
                            + "`ERROR_1013`(요청의 모든 item이 이미 타임라인에 저장됨 — 추가할 신규 없음) · "
                            + "`ERROR_1016`(같은 날짜의 타임라인 작업이 진행 중 — 잠시 후 재시도)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "502",
                    description = "지오코딩(지도 API) 호출 실패로 draft 생성 실패. 재시도 가능성으로 코드가 나뉜다 — "
                            + "`ERROR_1014`(전이적 실패 — 재시도로 해결될 수 있음) · "
                            + "`ERROR_1015`(영구적 실패 — 쿼터·키·응답 오류, 즉시 재시도는 무의미)")
    })
    @PostMapping
    ResponseEntity<ApiResponse<CreateDraftTaskResponse>> createDraftTask(
            @Parameter(description = "API 버전", example = "v1") @PathVariable String applicationVersion,
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
                    description = "`ERROR_1004`(최대 장수 초과) · `ERROR_1005`(장당 크기 초과) · "
                            + "`ERROR_1007`(미지원 포맷 — JPG/PNG/WebP만) · `ERROR_0400`(필수값 누락)")
    })
    @PostMapping("/photo-uploads")
    ResponseEntity<ApiResponse<PhotoUploadCreateResponse>> createPhotoUploads(
            @Parameter(description = "API 버전", example = "v1") @PathVariable String applicationVersion,
            @RequestBody PhotoUploadCreateRequest request);

    @Operation(summary = "draft 작업 상태 폴링",
            description = "PROCESSING이면 status만, SUCCESS면 result(그날 타임라인), FAILED면 body.error에 실패 분류 코드가 담긴다"
                    + "(FAILED도 HTTP 200 + COMMON_0000). body.error 코드: `ERROR_1008`(AI가 실패 보고) · "
                    + "`ERROR_1009`(AI 요청 전달 실패) · `ERROR_1010`/`ERROR_1011`(서버 처리 실패). "
                    + "미지의 코드는 제네릭 실패로 처리한다(전방 호환).")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "폴링 성공(작업 상태는 body.status)", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "`ERROR_1001`(작업 없음 — 만료 포함) · "
                            + "`ERROR_0404`(SUCCESS 작업의 결과 기록이 삭제됐거나 결과 ID가 없는 구버전 작업 — "
                            + "클라이언트는 1001=작업 소멸, 0404=결과 소멸로 구분)")
    })
    @GetMapping("/{taskId}")
    ResponseEntity<ApiResponse<DraftTaskStatusResponse>> pollDraftTask(
            @Parameter(description = "API 버전", example = "v1") @PathVariable String applicationVersion,
            @Parameter(description = "draft 작업 ID (생성 응답의 taskId)") @PathVariable String taskId);
}
