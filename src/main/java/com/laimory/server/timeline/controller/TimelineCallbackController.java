package com.laimory.server.timeline.controller;

import com.laimory.server.common.ApiUrls;
import com.laimory.server.timeline.dto.DraftTaskCallbackRequest;
import com.laimory.server.timeline.service.TimelineCallbackService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 카드 생성 콜백(서버간 통신). 공유 secret 헤더로 보호된다({@code CallbackSecretInterceptor}가 {@code /s/**} 검증).
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
                                         @RequestBody DraftTaskCallbackRequest request) {
        timelineCallbackService.handleCallback(applicationVersion, taskId, request);
        return ResponseEntity.ok().build();
    }
}
