package com.laimory.server.inquiry.controller;

import com.laimory.server.common.ApiResponse;
import com.laimory.server.common.ApiUrls;
import com.laimory.server.inquiry.dto.InquiryAttachmentUploadCreateRequest;
import com.laimory.server.inquiry.dto.InquiryAttachmentUploadCreateResponse;
import com.laimory.server.inquiry.dto.InquiryCreateRequest;
import com.laimory.server.user.CurrentSubject;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 문의 접수 API의 문서·계약(구현은 {@link InquiryController}).
 *
 * <p>접수만 있고 "내 문의" 조회는 없다 — 답변은 서버에 저장되지 않고 접수한 이메일로 관리자가 직접
 * 회신한다. 첨부는 사진 업로드와 같은 presigned PUT 흐름이며 owner는 {@code @CurrentSubject}가 JWT
 * principal에서 해석한 subject다(클라이언트 입력 아님).
 */
@Tag(name = "Inquiries", description = "문의 — 첨부 업로드 URL 발급과 접수")
@SecurityRequirement(name = "bearerAuth")
@RequestMapping(ApiUrls.AUTHENTICATED_API_URL + "/inquiries")
public interface InquiryApi {

    @Operation(summary = "문의 첨부 업로드 URL 발급",
            description = "첨부할 사진 목록(contentType·size)을 받아 S3 presigned PUT URL을 발급한다. "
                    + "클라이언트는 발급된 URL로 사진 바이너리를 직접 PUT 업로드한 뒤, 응답의 filename을 "
                    + "접수 요청의 attachmentFilenames에 그대로 담는다. 요청당 최대 3장.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "발급 성공", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "`-1004`(최대 3장 초과) · `-1005`(장당 크기 초과) · "
                            + "`-1007`(미지원 포맷 — JPG/PNG/WebP만) · `-400`(필수값 누락)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "`-2001` — 인증 필요(Bearer access token 부재/무효/만료)")
    })
    @PostMapping("/attachment-uploads")
    ResponseEntity<ApiResponse<InquiryAttachmentUploadCreateResponse>> createAttachmentUploads(
            @Parameter(description = "API 버전", example = "v1") @PathVariable String applicationVersion,
            @Parameter(hidden = true) @CurrentSubject UUID subjectId,
            @RequestBody InquiryAttachmentUploadCreateRequest request);

    @Operation(summary = "문의 접수",
            description = "분류·답장 이메일·본문·첨부 filename(0~3개)을 받아 문의를 접수한다. 201이 곧 접수 완료다. "
                    + "답변은 이 API로 조회할 수 없다 — 관리자가 입력한 이메일로 직접 회신한다. 서버는 첨부의 "
                    + "S3 업로드 완료를 확인하지 않으며, 같은 내용의 재요청은 새 문의로 접수된다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201",
                    description = "접수 완료(body 없음)", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "`-400`(category/email/body 누락·형식·길이 위반, 첨부 filename 형식 오류·중복) · "
                            + "`-1004`(첨부 3장 초과)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "`-2001` — 인증 필요(Bearer access token 부재/무효/만료)")
    })
    @PostMapping
    ResponseEntity<ApiResponse<Void>> createInquiry(
            @Parameter(description = "API 버전", example = "v1") @PathVariable String applicationVersion,
            @Parameter(hidden = true) @CurrentSubject UUID subjectId,
            @Valid @RequestBody InquiryCreateRequest request);
}
