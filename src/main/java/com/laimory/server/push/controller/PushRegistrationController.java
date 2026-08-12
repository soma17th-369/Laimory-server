package com.laimory.server.push.controller;

import com.laimory.server.common.ApiResponse;
import com.laimory.server.common.id.SubjectId;
import com.laimory.server.push.dto.PushRegistrationRequest;
import com.laimory.server.push.service.PushRegistrationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * FCM 푸시 등록 API 구현. HTTP 문서·계약은 {@link PushRegistrationApi}.
 *
 * <p>컨트롤러 파라미터의 subjectId는 클라이언트 값이 아니라 {@code @CurrentSubject}가 JWT principal을
 * 해석한 결과다. 등록 owner 결합·해제 조건은 전부 이 subject를 기준으로 한다.
 */
@RestController
@RequiredArgsConstructor
public class PushRegistrationController implements PushRegistrationApi {

    private final PushRegistrationService pushRegistrationService;

    @Override
    public ResponseEntity<ApiResponse<Void>> registerPushRegistration(
            String applicationVersion, SubjectId subjectId, PushRegistrationRequest request) {
        pushRegistrationService.register(applicationVersion, subjectId, request.firebaseInstallationId());
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @Override
    public ResponseEntity<ApiResponse<Void>> unregisterPushRegistration(
            String applicationVersion, SubjectId subjectId, PushRegistrationRequest request) {
        pushRegistrationService.unregister(applicationVersion, subjectId, request.firebaseInstallationId());
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
