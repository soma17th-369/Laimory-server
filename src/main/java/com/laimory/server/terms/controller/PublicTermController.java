package com.laimory.server.terms.controller;

import com.laimory.server.common.ApiResponse;
import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.terms.TermType;
import com.laimory.server.terms.dto.TermListResponse;
import com.laimory.server.terms.dto.TermResponse;
import com.laimory.server.terms.service.TermDocumentService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 공개 약관 조회 API 구현. HTTP 문서·계약은 {@link PublicTermApi}. */
@RestController
@RequiredArgsConstructor
public class PublicTermController implements PublicTermApi {

    private final TermDocumentService termDocumentService;

    @Override
    public ResponseEntity<ApiResponse<TermListResponse>> getCurrentTerms(String applicationVersion,
                                                                         List<TermType> termTypes) {
        if (termTypes.isEmpty() || termTypes.stream().distinct().count() != termTypes.size()) {
            throw new BusinessException(ExceptionType.VALIDATION_FAILED);
        }
        return ResponseEntity.ok(ApiResponse.success(new TermListResponse(
                termDocumentService.findCurrentDocuments(applicationVersion, termTypes).stream()
                        .map(TermResponse::from)
                        .toList())));
    }
}
