package com.laimory.server.timeline.controller;

import com.laimory.server.timeline.dto.DraftTaskCallbackRequest;
import com.laimory.server.timeline.service.TimelineCallbackService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 타임라인 이벤트 생성 콜백(서버간 통신) 구현. HTTP 문서·계약은 {@link TimelineCallbackApi}.
 */
@RestController
@RequiredArgsConstructor
public class TimelineCallbackController implements TimelineCallbackApi {

    private final TimelineCallbackService timelineCallbackService;

    @Override
    public ResponseEntity<Void> callback(String applicationVersion, String taskId,
                                         String taskToken, DraftTaskCallbackRequest request) {
        timelineCallbackService.handleCallback(applicationVersion, taskId, taskToken, request);
        return ResponseEntity.ok().build();
    }
}
