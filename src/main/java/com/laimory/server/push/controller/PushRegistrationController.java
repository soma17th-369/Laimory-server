package com.laimory.server.push.controller;

import com.laimory.server.common.ApiResponse;
import com.laimory.server.push.dto.PushRegistrationRequest;
import com.laimory.server.push.service.PushRegistrationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * FCM 푸시 등록 API 구현. HTTP 문서·계약은 {@link PushRegistrationApi}.
 *
 * <p>userId는 클라이언트가 보내는 값이 아니라 <b>인증된 JWT principal({@code Long})을 컨트롤러가
 * 서비스에 전달</b>한다 — 등록 owner 결합·해제 조건은 전부 이 값 기준이다.
 */
@RestController
@RequiredArgsConstructor
public class PushRegistrationController implements PushRegistrationApi {

    private final PushRegistrationService pushRegistrationService;

    @Override
    public ResponseEntity<ApiResponse<Void>> registerPushRegistration(
            String applicationVersion, Long userId, PushRegistrationRequest request) {
        pushRegistrationService.register(applicationVersion, userId, request.firebaseInstallationId());
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @Override
    public ResponseEntity<ApiResponse<Void>> unregisterPushRegistration(
            String applicationVersion, Long userId, PushRegistrationRequest request) {
        pushRegistrationService.unregister(applicationVersion, userId, request.firebaseInstallationId());
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
