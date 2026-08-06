package com.laimory.server.timeline.controller;

import com.laimory.server.common.ApiResponse;
import com.laimory.server.timeline.dto.AiUserMemoryUpdateResultRequest;
import com.laimory.server.timeline.service.UserMemoryUpdateResultService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * User Memory 갱신 결과 저장 API 구현. HTTP 문서·계약은 {@link UserMemoryUpdateApi}.
 *
 * <p>{@code /s/api}라 request principal이 없다 — 소유자는 Redis task가 들고 있고 인증은 Task-Token이다.
 */
@RestController
@RequiredArgsConstructor
public class UserMemoryUpdateController implements UserMemoryUpdateApi {

    private final UserMemoryUpdateResultService userMemoryUpdateResultService;

    @Override
    public ResponseEntity<ApiResponse<Void>> result(String applicationVersion, String taskId,
                                                    String taskToken,
                                                    AiUserMemoryUpdateResultRequest request) {
        userMemoryUpdateResultService.applyResult(applicationVersion, taskId, taskToken, request);
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
