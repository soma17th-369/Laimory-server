package com.laimory.server.terms.controller;

import com.laimory.server.common.ApiResponse;
import com.laimory.server.terms.TermStage;
import com.laimory.server.terms.dto.TermListResponse;
import com.laimory.server.terms.dto.TermResponse;
import com.laimory.server.terms.service.TermContentUrlFactory;
import com.laimory.server.terms.service.TermDocumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 공개 약관 조회 API 구현. HTTP 문서·계약은 {@link PublicTermApi}. */
@RestController
@RequiredArgsConstructor
public class PublicTermController implements PublicTermApi {

    private final TermDocumentService termDocumentService;
    private final TermContentUrlFactory termContentUrlFactory;

    @Override
    public ResponseEntity<ApiResponse<TermListResponse>> getCurrentTerms(String applicationVersion,
                                                                         TermStage stage) {
        return ResponseEntity.ok(ApiResponse.success(new TermListResponse(
                termDocumentService.findCurrentDocuments(applicationVersion, stage).stream()
                        .map(document -> TermResponse.from(document, termContentUrlFactory.create(
                                document.getTermType(), document.getVersion())))
                        .toList())));
    }
}
