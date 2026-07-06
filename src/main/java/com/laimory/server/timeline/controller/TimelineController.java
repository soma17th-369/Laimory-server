package com.laimory.server.timeline.controller;

import com.laimory.server.common.ApiResponse;
import com.laimory.server.common.ApiUrls;
import com.laimory.server.timeline.dto.CreateDraftTaskRequest;
import com.laimory.server.timeline.dto.CreateDraftTaskResponse;
import com.laimory.server.timeline.dto.DraftTaskStatusResponse;
import com.laimory.server.timeline.dto.PhotoUploadCreateRequest;
import com.laimory.server.timeline.dto.PhotoUploadCreateResponse;
import com.laimory.server.timeline.service.PhotoUploadService;
import com.laimory.server.timeline.service.TimelineDraftTaskService;
import com.laimory.server.timeline.service.TimelineDraftTaskPollingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 타임라인 draft 작성 작업 API(작업 생성·폴링·사진 업로드 발급). 콜백은 서버간 통신이라 {@link TimelineCallbackController}에 분리.
 *
 * <p>모든 엔드포인트가 userId에 종속된 작업이라 인증 prefix({@code /a/api})에 둔다(사진 presign은 S3 객체를
 * 만들어내므로 공개 노출 시 남발/비용 위험 — 인증 경계로 보호). 사용자 인증 도입 전까지는 {@code TimelineDefaults}의
 * 고정 userId를 쓰지만 경로는 인증 prefix로 고정한다.
 *
 * <p>버전은 {@code @PathVariable applicationVersion}으로 받아 그대로 Service에 넘긴다 — 버전별 분기는 Service 책임.
 */
@Tag(name = "Timeline Draft", description = "타임라인 draft 작성 작업 — 생성·폴링·사진 업로드 URL 발급")
@RestController
@RequiredArgsConstructor
@RequestMapping(ApiUrls.AUTHENTICATED_API_URL + "/timeline/drafts")
public class TimelineController {

    private final TimelineDraftTaskService timelineDraftTaskService;
    private final TimelineDraftTaskPollingService timelineDraftTaskPollingService;
    private final PhotoUploadService photoUploadService;

    @Operation(summary = "draft 작업 생성",
            description = "sourceItems(하루 기록 원천: 위치·이동·사진·건강·알림 등)를 받아 AI 타임라인 생성 작업을 시작한다. "
                    + "202로 반환된 taskId를 `GET /{taskId}`로 폴링해 결과를 조회한다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202",
                    description = "작업 접수 — body.taskId로 폴링", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "`ERROR_0400` — 필수값 누락·불량 입력(recordAt/recordTimeZone/sourceItems 등)",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "`ERROR_1003` — 해당 날짜의 하루 기록이 이미 SAVED",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    @PostMapping
    public ResponseEntity<ApiResponse<CreateDraftTaskResponse>> createDraftTask(
            @Parameter(description = "API 버전", example = "v1") @PathVariable String applicationVersion,
            @RequestBody CreateDraftTaskRequest request) {
        String taskId = timelineDraftTaskService.createDraftTask(
                applicationVersion, request.recordAt(), request.recordTimeZone(), request.sourceItems());
        return ResponseEntity.accepted().body(ApiResponse.success(new CreateDraftTaskResponse(taskId)));
    }

    @Operation(summary = "사진 업로드 URL 발급",
            description = "업로드할 사진 목록(contentType·size)을 받아 S3 presigned PUT URL을 발급한다. "
                    + "클라이언트는 발급된 URL로 사진 바이너리를 직접 PUT 업로드한다(URL 유효시간 내).")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "발급 성공", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "`ERROR_1004`(최대 장수 초과) · `ERROR_1005`(장당 크기 초과) · "
                            + "`ERROR_1007`(미지원 포맷 — JPG/PNG/WebP만) · `ERROR_0400`(필수값 누락)",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    @PostMapping("/photo-uploads")
    public ResponseEntity<ApiResponse<PhotoUploadCreateResponse>> createPhotoUploads(
            @Parameter(description = "API 버전", example = "v1") @PathVariable String applicationVersion,
            @RequestBody PhotoUploadCreateRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                photoUploadService.createUploads(applicationVersion, request.photos())));
    }

    @Operation(summary = "draft 작업 상태 폴링",
            description = "PROCESSING이면 status만, SUCCESS면 result(그날 타임라인), FAILED면 body.error에 실패 분류 코드가 담긴다"
                    + "(FAILED도 HTTP 200 + COMMON_0000). body.error 코드: `ERROR_1008`(AI가 실패 보고) · "
                    + "`ERROR_1009`(AI 요청 전달 실패) · `ERROR_1010`/`ERROR_1011`(서버 처리 실패). "
                    + "미지의 코드는 제네릭 실패로 처리한다(전방 호환).")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "폴링 성공(작업 상태는 body.status)", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "`ERROR_1001` — 작업 없음(만료 포함)",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    @GetMapping("/{taskId}")
    public ResponseEntity<ApiResponse<DraftTaskStatusResponse>> pollDraftTask(
            @Parameter(description = "API 버전", example = "v1") @PathVariable String applicationVersion,
            @Parameter(description = "draft 작업 ID (생성 응답의 taskId)") @PathVariable String taskId) {
        return ResponseEntity.ok(ApiResponse.success(timelineDraftTaskPollingService.poll(applicationVersion, taskId)));
    }
}
