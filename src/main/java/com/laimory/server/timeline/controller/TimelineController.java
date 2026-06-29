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
@RestController
@RequiredArgsConstructor
@RequestMapping(ApiUrls.AUTHENTICATED_API_URL + "/timeline/drafts")
public class TimelineController {

    private final TimelineDraftTaskService timelineDraftTaskService;
    private final TimelineDraftTaskPollingService timelineDraftTaskPollingService;
    private final PhotoUploadService photoUploadService;

    @PostMapping
    public ResponseEntity<ApiResponse<CreateDraftTaskResponse>> createDraftTask(
            @PathVariable String applicationVersion,
            @RequestBody CreateDraftTaskRequest request) {
        String taskId = timelineDraftTaskService.createDraftTask(
                applicationVersion, request.recordAt(), request.recordTimeZone(), request.sourceItems());
        return ResponseEntity.accepted().body(ApiResponse.success(new CreateDraftTaskResponse(taskId)));
    }

    @PostMapping("/photo-uploads")
    public ResponseEntity<ApiResponse<PhotoUploadCreateResponse>> createPhotoUploads(
            @PathVariable String applicationVersion,
            @RequestBody PhotoUploadCreateRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                photoUploadService.createUploads(applicationVersion, request.photos())));
    }

    @GetMapping("/{taskId}")
    public ResponseEntity<ApiResponse<DraftTaskStatusResponse>> pollDraftTask(
            @PathVariable String applicationVersion,
            @PathVariable String taskId) {
        return ResponseEntity.ok(ApiResponse.success(timelineDraftTaskPollingService.poll(applicationVersion, taskId)));
    }
}
