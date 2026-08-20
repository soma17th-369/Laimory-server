package com.laimory.server.push.controller;

import com.laimory.server.common.ApiResponse;
import com.laimory.server.push.dto.PushEnabledRequest;
import com.laimory.server.push.dto.PushSettingsResponse;
import com.laimory.server.push.service.PushSettingService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 푸시 수신 설정 API 구현. HTTP 문서·계약은 {@link PushSettingApi}.
 *
 * <p>subjectId는 클라이언트 값이 아니라 {@code @CurrentSubject}가 JWT principal을 해석한 결과다.
 * 설정의 owner 판정은 전부 이 subject 하나를 기준으로 한다.
 */
@RestController
@RequiredArgsConstructor
public class PushSettingController implements PushSettingApi {

    private final PushSettingService pushSettingService;

    @Override
    public ResponseEntity<ApiResponse<PushSettingsResponse>> getPushSettings(
            String applicationVersion, UUID subjectId) {
        return ResponseEntity.ok(ApiResponse.success(
                pushSettingService.getSettings(applicationVersion, subjectId)));
    }

    @Override
    public ResponseEntity<ApiResponse<Void>> updatePushEnabled(
            String applicationVersion, UUID subjectId, PushEnabledRequest request) {
        pushSettingService.updatePushEnabled(applicationVersion, subjectId, request.enabled());
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @Override
    public ResponseEntity<ApiResponse<Void>> updateDailyReminderEnabled(
            String applicationVersion, UUID subjectId, PushEnabledRequest request) {
        pushSettingService.updateDailyReminderEnabled(applicationVersion, subjectId, request.enabled());
        return ResponseEntity.ok(ApiResponse.success(null));
    }

}
