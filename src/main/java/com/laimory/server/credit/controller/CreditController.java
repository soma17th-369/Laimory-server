package com.laimory.server.credit.controller;

import com.laimory.server.common.ApiResponse;
import com.laimory.server.credit.dto.CreditResponse;
import com.laimory.server.credit.service.CreditService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 크레딧 API 구현. HTTP 문서·계약은 {@link CreditApi}.
 *
 * <p>subjectId는 클라이언트 값이 아니라 {@code @CurrentSubject}가 JWT principal을 해석한 결과다.
 */
@RestController
@RequiredArgsConstructor
public class CreditController implements CreditApi {

    private final CreditService creditService;

    @Override
    public ResponseEntity<ApiResponse<CreditResponse>> getCredit(String applicationVersion, UUID subjectId) {
        return ResponseEntity.ok(ApiResponse.success(creditService.getCredit(applicationVersion, subjectId)));
    }
}
