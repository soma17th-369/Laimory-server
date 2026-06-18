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
 * <p>POST(작업 생성)·GET(폴링)은 공개 API(/api/{ver}), 콜백은 서버간 통신(/s/api/{ver}, secret 인터셉터가 보호)이다.
 * 버전은 하드코딩하지 않고 정규식으로 제약한 path variable {@code {applicationVersion:v\d+}}로 받아 그대로 Service에 넘긴다 —
 * 버전별 동작 분기는 Service 계층 책임이다(컨트롤러는 버전 해석 로직을 두지 않는다).
 * 클래스 단위 prefix가 다르므로(/api vs /s/api) 매핑은 메서드별 전체 경로로 둔다.
 */
@RestController
@RequiredArgsConstructor
public class TimelineController {

    // 버전 세그먼트(정규식 제약). 다른 버전 형식을 쓰려면 이 한 곳만 바꾼다.
    private static final String VERSION = "{applicationVersion:v\\d+}";
    private static final String DRAFT_TASKS_PATH = "/api/" + VERSION + "/timeline/daily-records/draft-tasks";

    private final TimelineDraftTaskService timelineDraftTaskService;
    private final TimelineCallbackService timelineCallbackService;
    private final TimelineDraftTaskPollingService timelineDraftTaskPollingService;

    @PostMapping(DRAFT_TASKS_PATH)
    public ResponseEntity<CreateDraftTaskResponse> createDraftTask(
            @PathVariable String applicationVersion,
            @RequestBody CreateDraftTaskRequest request) {
        String taskId = timelineDraftTaskService.createDraftTask(
                applicationVersion, request.recordDate(), request.sourceItems());
        return ResponseEntity.accepted().body(new CreateDraftTaskResponse(taskId));
    }

    @PostMapping("/s" + DRAFT_TASKS_PATH + "/{taskId}/callback")
    public ResponseEntity<Void> callback(@PathVariable String applicationVersion,
                                         @PathVariable String taskId,
                                         @RequestBody DraftTaskCallbackRequest request) {
        timelineCallbackService.handleCallback(applicationVersion, taskId, request);
        return ResponseEntity.ok().build();
    }

    @GetMapping(DRAFT_TASKS_PATH + "/{taskId}")
    public ResponseEntity<DraftTaskStatusResponse> pollDraftTask(
            @PathVariable String applicationVersion,
            @PathVariable String taskId) {
        return ResponseEntity.ok(timelineDraftTaskPollingService.poll(applicationVersion, taskId));
    }
}
