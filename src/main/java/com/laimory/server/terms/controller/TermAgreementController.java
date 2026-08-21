package com.laimory.server.terms.controller;

import com.laimory.server.common.ApiResponse;
import com.laimory.server.terms.dto.TermAgreementCreateRequest;
import com.laimory.server.terms.dto.TermAgreementHistoryResponse;
import com.laimory.server.terms.dto.TermAgreementResponse;
import com.laimory.server.terms.service.TermAgreementCommand;
import com.laimory.server.terms.service.TermAgreementService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 약관 동의 API 구현. HTTP 문서·계약은 {@link TermAgreementApi}.
 *
 * <p>userId는 클라이언트 값이 아니라 JWT 인증 principal이다. 요청 shape 검증(빈 배열·중복 등)은
 * Service validation 한 곳이 담당한다(컨트롤러 중복 검증 금지).
 */
@RestController
@RequiredArgsConstructor
public class TermAgreementController implements TermAgreementApi {

    private final TermAgreementService termAgreementService;

    @Override
    public ResponseEntity<ApiResponse<Void>> agreeToTerms(String applicationVersion, Long userId,
                                                          TermAgreementCreateRequest request) {
        termAgreementService.agreeToTerms(applicationVersion, userId, toCommands(request));
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @Override
    public ResponseEntity<ApiResponse<TermAgreementHistoryResponse>> getMyAgreements(String applicationVersion,
                                                                                     Long userId) {
        return ResponseEntity.ok(ApiResponse.success(new TermAgreementHistoryResponse(
                termAgreementService.getHistory(applicationVersion, userId).stream()
                        .map(TermAgreementResponse::from)
                        .toList())));
    }

    /** null 요소·필드는 그대로 전달한다 — 400 판정은 Service validation 한 곳이 담당한다. */
    private static List<TermAgreementCommand> toCommands(TermAgreementCreateRequest request) {
        if (request.agreements() == null) {
            return null;
        }
        return request.agreements().stream()
                .map(agreement -> agreement == null
                        ? null : new TermAgreementCommand(agreement.termType(), agreement.version()))
                .toList();
    }
}
