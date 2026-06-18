package com.laimory.server.timeline.controller;

import com.laimory.server.common.ApiUrls;
import com.laimory.server.timeline.dto.DraftTaskCallbackRequest;
import com.laimory.server.timeline.service.TimelineCallbackService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 카드 생성 콜백(서버간 통신). task별 one-time Callback-Token 헤더로 검증한다(서비스가 해시 비교; 누락/불일치 401).
 *
 * <p>공개 API와 prefix가 다르므로(/s/api vs /api) 별도 컨트롤러로 두고 클래스 레벨 {@code @RequestMapping}을 쓴다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping(ApiUrls.SERVER_API_URL + "/timeline/daily-records/draft-tasks")
public class TimelineCallbackController {

    private final TimelineCallbackService timelineCallbackService;

    @PostMapping("/{taskId}/callback")
    public ResponseEntity<Void> callback(@PathVariable String applicationVersion,
                                         @PathVariable String taskId,
                                         @RequestHeader(value = "Callback-Token", required = false)
                                         String callbackToken,
                                         @RequestBody DraftTaskCallbackRequest request) {
        timelineCallbackService.handleCallback(applicationVersion, taskId, callbackToken, request);
        return ResponseEntity.ok().build();
    }
}
