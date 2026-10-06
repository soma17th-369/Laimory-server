package com.laimory.server.credit.controller;

import com.laimory.server.common.ApiResponse;
import com.laimory.server.credit.dto.CreditCostsResponse;
import com.laimory.server.credit.service.CreditService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 공개 크레딧 비용 조회 API 구현. HTTP 문서·계약은 {@link PublicCreditCostApi}. */
@RestController
@RequiredArgsConstructor
public class PublicCreditCostController implements PublicCreditCostApi {

    private final CreditService creditService;

    @Override
    public ResponseEntity<ApiResponse<CreditCostsResponse>> getCosts(String applicationVersion) {
        return ResponseEntity.ok(ApiResponse.success(creditService.getCosts(applicationVersion)));
    }
}
