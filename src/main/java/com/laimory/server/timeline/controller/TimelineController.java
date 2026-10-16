package com.laimory.server.timeline.controller;

import com.laimory.server.common.ApiResponse;
import com.laimory.server.timeline.dto.CheckDraftRequest;
import com.laimory.server.timeline.dto.CheckDraftResponse;
import com.laimory.server.timeline.dto.CreateDraftTaskRequest;
import com.laimory.server.timeline.dto.CreateDraftTaskResponse;
import com.laimory.server.timeline.dto.DraftTaskListResponse;
import com.laimory.server.timeline.dto.DraftTaskStatusResponse;
import com.laimory.server.timeline.dto.PhotoUploadCreateRequest;
import com.laimory.server.timeline.dto.PhotoUploadCreateResponse;
import com.laimory.server.timeline.service.PhotoUploadService;
import com.laimory.server.timeline.service.TimelineDraftCheckService;
import com.laimory.server.timeline.service.TimelineDraftTaskListService;
import com.laimory.server.timeline.service.TimelineDraftTaskService;
import com.laimory.server.timeline.service.TimelineDraftTaskPollingService;
import java.util.UUID;
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
    private final TimelineDraftCheckService timelineDraftCheckService;

    @Override
    public ResponseEntity<ApiResponse<CreateDraftTaskResponse>> createDraftTask(
            String applicationVersion, UUID subjectId, CreateDraftTaskRequest request) {
        return ResponseEntity.accepted().body(ApiResponse.success(timelineDraftTaskService.createDraftTask(
                applicationVersion, subjectId, request.recordDate(), request.recordAt(), request.recordTimeZone(),
                request.timelineWindow(), request.sourceItems())));
    }

    /** subjectId는 보호 API 공통 계약(hidden principal)으로 받지만 판정은 받은 기록만 보므로 넘기지 않는다. */
    @Override
    public ResponseEntity<ApiResponse<CheckDraftResponse>> checkDraft(
            String applicationVersion, UUID subjectId, CheckDraftRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                timelineDraftCheckService.check(applicationVersion, request.sourceItems())));
    }

    @Override
    public ResponseEntity<ApiResponse<PhotoUploadCreateResponse>> createPhotoUploads(
            String applicationVersion, UUID subjectId, PhotoUploadCreateRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                photoUploadService.createUploads(applicationVersion, subjectId, request.photos())));
    }

    @Override
    public ResponseEntity<ApiResponse<DraftTaskListResponse>> listProcessingDraftTasks(
            String applicationVersion, UUID subjectId) {
        return ResponseEntity.ok(ApiResponse.success(
                timelineDraftTaskListService.list(applicationVersion, subjectId)));
    }

    @Override
    public ResponseEntity<ApiResponse<DraftTaskStatusResponse>> pollDraftTask(
            String applicationVersion, UUID subjectId, String taskId) {
        return ResponseEntity.ok(ApiResponse.success(
                timelineDraftTaskPollingService.poll(applicationVersion, subjectId, taskId)));
    }
}
