package com.laimory.server.push.controller;

import com.laimory.server.common.ApiResponse;
import com.laimory.server.push.dto.NotificationConsentResultResponse;
import com.laimory.server.push.dto.PushOptOutRequest;
import com.laimory.server.push.service.PushOptOutService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 비로그인 수신거부 API 구현. HTTP 문서·계약은 {@link PushOptOutApi}.
 * 대상 subject는 요청 body가 아니라 FID로 잠근 현재 등록 행에서 해석한다.
 */
@RestController
@RequiredArgsConstructor
public class PushOptOutController implements PushOptOutApi {

    private final PushOptOutService pushOptOutService;

    @Override
    public ResponseEntity<ApiResponse<List<NotificationConsentResultResponse>>> optOut(
            String applicationVersion, PushOptOutRequest request) {
        return ResponseEntity.ok(ApiResponse.success(NotificationConsentResultResponse.from(
                pushOptOutService.optOut(applicationVersion,
                        request.firebaseInstallationId(), request.optOutToken()))));
    }
}
