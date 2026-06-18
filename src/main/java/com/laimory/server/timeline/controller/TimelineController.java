package com.laimory.server.timeline.controller;

import com.laimory.server.timeline.dto.CreateDraftTaskRequest;
import com.laimory.server.timeline.dto.CreateDraftTaskResponse;
import com.laimory.server.timeline.dto.DraftTaskCallbackRequest;
import com.laimory.server.timeline.dto.DraftTaskStatusResponse;
import com.laimory.server.timeline.service.TimelineCallbackService;
import com.laimory.server.timeline.service.TimelineDraftTaskPollingService;
import com.laimory.server.timeline.service.TimelineDraftTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 타임라인 draft 작성 작업 API.
 *
 * <p>POST(작업 생성)·GET(폴링)은 공개 API(/api/v1), 콜백은 서버간 통신(/s/api/v1, secret 인터셉터가 보호)이다.
 * 클래스 단위 prefix가 다르므로(/api/v1 vs /s/api/v1) 매핑은 메서드별 전체 경로로 둔다.
 */
@RestController
@RequiredArgsConstructor
public class TimelineController {

    private static final String DRAFT_TASKS_PATH = "/api/v1/timeline/daily-records/draft-tasks";

    private final TimelineDraftTaskService timelineDraftTaskService;
    private final TimelineCallbackService timelineCallbackService;
    private final TimelineDraftTaskPollingService timelineDraftTaskPollingService;

    @PostMapping(DRAFT_TASKS_PATH)
    public ResponseEntity<CreateDraftTaskResponse> createDraftTask(
            @RequestBody CreateDraftTaskRequest request) {
        String taskId = timelineDraftTaskService.createDraftTask(request.recordDate(), request.sourceItems());
        return ResponseEntity.accepted().body(new CreateDraftTaskResponse(taskId));
    }

    @PostMapping("/s" + DRAFT_TASKS_PATH + "/{taskId}/callback")
    public ResponseEntity<Void> callback(@PathVariable String taskId,
                                         @RequestBody DraftTaskCallbackRequest request) {
        timelineCallbackService.handleCallback(taskId, request);
        return ResponseEntity.ok().build();
    }

    @GetMapping(DRAFT_TASKS_PATH + "/{taskId}")
    public ResponseEntity<DraftTaskStatusResponse> pollDraftTask(@PathVariable String taskId) {
        return ResponseEntity.ok(timelineDraftTaskPollingService.poll(taskId));
    }
}
