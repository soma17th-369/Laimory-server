package com.laimory.server.timeline.controller;

import com.laimory.server.timeline.dto.AiTimelineResultRequest;
import com.laimory.server.timeline.dto.AiTimelineResultResponse;
import com.laimory.server.timeline.dto.AiTimelineTaskInputResponse;
import com.laimory.server.timeline.service.TimelineAiResultService;
import com.laimory.server.timeline.service.TimelineAiTaskInputService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 작업 입력 조회·결과 저장(서버간 통신) 구현. HTTP 문서·계약은 {@link TimelineAiTaskApi}.
 */
@RestController
@RequiredArgsConstructor
public class TimelineAiTaskController implements TimelineAiTaskApi {

    private final TimelineAiTaskInputService timelineAiTaskInputService;
    private final TimelineAiResultService timelineAiResultService;

    @Override
    public ResponseEntity<AiTimelineTaskInputResponse> input(String applicationVersion, String taskId,
                                                             String taskToken) {
        return ResponseEntity.ok(timelineAiTaskInputService.getInput(applicationVersion, taskId, taskToken));
    }

    @Override
    public ResponseEntity<AiTimelineResultResponse> result(String applicationVersion, String taskId,
                                                           String taskToken, AiTimelineResultRequest request) {
        return ResponseEntity.ok(
                timelineAiResultService.storeResult(applicationVersion, taskId, taskToken, request));
    }
}
