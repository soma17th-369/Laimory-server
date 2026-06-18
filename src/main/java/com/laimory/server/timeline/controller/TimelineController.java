package com.laimory.server.timeline.controller;

import com.laimory.server.common.ApiUrls;
import com.laimory.server.timeline.dto.CreateDraftTaskRequest;
import com.laimory.server.timeline.dto.CreateDraftTaskResponse;
import com.laimory.server.timeline.dto.DraftTaskStatusResponse;
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
 * 타임라인 draft 작성 작업 공개 API(작업 생성·폴링). 콜백은 서버간 통신이라 {@link TimelineCallbackController}에 분리.
 *
 * <p>버전은 {@code @PathVariable applicationVersion}으로 받아 그대로 Service에 넘긴다 — 버전별 분기는 Service 책임.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping(ApiUrls.API_URL + "/timeline/drafts")
public class TimelineController {

    private final TimelineDraftTaskService timelineDraftTaskService;
    private final TimelineDraftTaskPollingService timelineDraftTaskPollingService;

    @PostMapping
    public ResponseEntity<CreateDraftTaskResponse> createDraftTask(
            @PathVariable String applicationVersion,
            @RequestBody CreateDraftTaskRequest request) {
        String taskId = timelineDraftTaskService.createDraftTask(
                applicationVersion, request.recordDate(), request.sourceItems());
        return ResponseEntity.accepted().body(new CreateDraftTaskResponse(taskId));
    }

    @GetMapping("/{taskId}")
    public ResponseEntity<DraftTaskStatusResponse> pollDraftTask(
            @PathVariable String applicationVersion,
            @PathVariable String taskId) {
        return ResponseEntity.ok(timelineDraftTaskPollingService.poll(applicationVersion, taskId));
    }
}
