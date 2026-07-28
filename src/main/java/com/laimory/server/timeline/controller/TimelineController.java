package com.laimory.server.timeline.controller;

import com.laimory.server.common.ApiResponse;
import com.laimory.server.timeline.dto.CreateDraftTaskRequest;
import com.laimory.server.timeline.dto.CreateDraftTaskResponse;
import com.laimory.server.timeline.dto.DraftTaskListResponse;
import com.laimory.server.timeline.dto.DraftTaskStatusResponse;
import com.laimory.server.timeline.dto.PhotoUploadCreateRequest;
import com.laimory.server.timeline.dto.PhotoUploadCreateResponse;
import com.laimory.server.timeline.service.PhotoUploadService;
import com.laimory.server.timeline.service.TimelineDraftTaskListService;
import com.laimory.server.timeline.service.TimelineDraftTaskService;
import com.laimory.server.timeline.service.TimelineDraftTaskPollingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 타임라인 draft 작성 작업 API 구현. HTTP 문서·계약은 {@link TimelineApi}.
 */
@RestController
@RequiredArgsConstructor
public class TimelineController implements TimelineApi {

    private final TimelineDraftTaskService timelineDraftTaskService;
    private final TimelineDraftTaskPollingService timelineDraftTaskPollingService;
    private final TimelineDraftTaskListService timelineDraftTaskListService;
    private final PhotoUploadService photoUploadService;

    @Override
    public ResponseEntity<ApiResponse<CreateDraftTaskResponse>> createDraftTask(
            String applicationVersion, Long userId, CreateDraftTaskRequest request) {
        String taskId = timelineDraftTaskService.createDraftTask(
                applicationVersion, userId, request.recordDate(), request.recordAt(), request.recordTimeZone(),
                request.timelineWindow(), request.sourceItems());
        return ResponseEntity.accepted().body(ApiResponse.success(new CreateDraftTaskResponse(taskId)));
    }

    @Override
    public ResponseEntity<ApiResponse<PhotoUploadCreateResponse>> createPhotoUploads(
            String applicationVersion, Long userId, PhotoUploadCreateRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                photoUploadService.createUploads(applicationVersion, userId, request.photos())));
    }

    @Override
    public ResponseEntity<ApiResponse<DraftTaskListResponse>> listProcessingDraftTasks(
            String applicationVersion, Long userId) {
        return ResponseEntity.ok(ApiResponse.success(
                timelineDraftTaskListService.list(applicationVersion, userId)));
    }

    @Override
    public ResponseEntity<ApiResponse<DraftTaskStatusResponse>> pollDraftTask(
            String applicationVersion, Long userId, String taskId) {
        return ResponseEntity.ok(ApiResponse.success(
                timelineDraftTaskPollingService.poll(applicationVersion, userId, taskId)));
    }
}
