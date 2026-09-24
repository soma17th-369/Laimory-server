package com.laimory.server.inquiry.controller;

import com.laimory.server.common.ApiResponse;
import com.laimory.server.inquiry.dto.InquiryAttachmentUploadCreateRequest;
import com.laimory.server.inquiry.dto.InquiryAttachmentUploadCreateResponse;
import com.laimory.server.inquiry.dto.InquiryCreateRequest;
import com.laimory.server.inquiry.service.InquiryAttachmentService;
import com.laimory.server.inquiry.service.InquiryService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 문의 접수 API 구현. HTTP 문서·계약은 {@link InquiryApi}. */
@RestController
@RequiredArgsConstructor
public class InquiryController implements InquiryApi {

    private final InquiryAttachmentService inquiryAttachmentService;
    private final InquiryService inquiryService;

    @Override
    public ResponseEntity<ApiResponse<InquiryAttachmentUploadCreateResponse>> createAttachmentUploads(
            String applicationVersion, UUID subjectId, InquiryAttachmentUploadCreateRequest request) {
        return ResponseEntity.ok(ApiResponse.success(inquiryAttachmentService.createUploads(
                applicationVersion, subjectId, request == null ? null : request.attachments())));
    }

    @Override
    public ResponseEntity<ApiResponse<Void>> createInquiry(String applicationVersion, UUID subjectId,
                                                           InquiryCreateRequest request) {
        inquiryService.register(applicationVersion, subjectId, request.email(), request.body(),
                request.attachmentFilenames());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(null));
    }
}
