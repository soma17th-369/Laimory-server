package com.laimory.server.inquiry.dto;

import com.laimory.server.inquiry.InquiryObjectKeys;
import com.laimory.server.inquiry.entity.Inquiry;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 문의 접수 요청 바디. 필수값·형식·길이는 HTTP 경계의 Bean Validation이 검사하고(위반은 400 {@code -400}),
 * 첨부 filename의 형식·중복은 서비스가 검사한다.
 */
public record InquiryCreateRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "user@example.com",
                description = "답장 받을 이메일. 회원 정보에 이메일이 없어 문의마다 입력받는다. 최대 255자")
        @NotBlank @Email @Size(max = Inquiry.EMAIL_MAX_LENGTH) String email,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "문의 본문(줄바꿈 포함 원문). 공백만은 불가, 최대 2,000자")
        @NotBlank @Size(max = Inquiry.BODY_MAX_LENGTH) String body,
        @Schema(description = "첨부 presign 응답의 filename 목록(요청 순서 유지). 누락·null·빈 배열은 첨부 없음, "
                + "최대 3개 — 초과 시 -1004, presign 형식이 아니거나 중복이면 -400")
        @Size(max = InquiryObjectKeys.MAX_ATTACHMENTS) List<String> attachmentFilenames
) {
}
