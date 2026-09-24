package com.laimory.server.admin;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.laimory.server.appconfig.AppConfigResponse;
import com.laimory.server.appconfig.AppConfigService;
import com.laimory.server.common.ApiResponse;
import com.laimory.server.inquiry.InquiryCategory;
import com.laimory.server.inquiry.entity.Inquiry;
import com.laimory.server.inquiry.service.InquiryAttachmentService;
import com.laimory.server.inquiry.service.InquiryService;
import com.laimory.server.notice.entity.Notice;
import com.laimory.server.notice.service.NoticeService;
import com.laimory.server.terms.TermType;
import com.laimory.server.terms.entity.TermDocument;
import com.laimory.server.terms.entity.TermDocumentId;
import com.laimory.server.terms.service.TermDocumentRegistrationService;
import com.laimory.server.terms.service.TermDocumentService;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Hidden
@RestController
@RequestMapping("/admin/api")
@ConditionalOnProperty(name = "APP_ADMIN_PORT")
@RequiredArgsConstructor
public class AdminApiController {

    private final TermDocumentService documents;
    private final TermDocumentRegistrationService registrations;
    private final AppConfigService appConfig;
    private final NoticeService notices;
    private final InquiryService inquiries;
    private final InquiryAttachmentService inquiryAttachments;

    @GetMapping("/terms")
    ApiResponse<List<TermGroup>> terms() {
        List<TermDocument> all = documents.findAllDocuments();
        return ApiResponse.success(Arrays.stream(TermType.values()).map(type -> {
            List<Document> history = all.stream().filter(doc -> doc.getTermType() == type)
                    .map(Document::from).toList();
            return new TermGroup(type, history.isEmpty() ? null : history.getFirst(), history);
        }).toList());
    }

    @PostMapping(value = "/terms", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<Publication> publish(@Valid @RequestBody PublishRequest request) {
        TermDocument saved = registrations.register(request.termType(), request.version(), request.title(),
                request.contentUrl(), request.publicationConfirmed());
        TermDocument current = documents.findCurrentDocuments(List.of(saved.getTermType())).getFirst();
        return ApiResponse.success(new Publication(Document.from(saved), Document.from(current)));
    }

    @GetMapping("/app-config")
    ApiResponse<AppConfigResponse> appConfig() {
        return ApiResponse.success(appConfig.getAppConfig("v1"));
    }

    @PutMapping(value = "/app-config", consumes = MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<AppConfigResponse> updateConfig(@Valid @RequestBody VersionRequest request) {
        return ApiResponse.success(appConfig.updateVersions(request.minAppVersion(), request.recommendAppVersion()));
    }

    /** 숨김 포함 전체 공지, 최신 순 — 공개 목록과 달리 노출 상태를 함께 보여준다. */
    @GetMapping("/notices")
    ApiResponse<List<AdminNotice>> notices() {
        return ApiResponse.success(notices.findAllNotices().stream().map(AdminNotice::from).toList());
    }

    @PostMapping(value = "/notices", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<AdminNotice> registerNotice(@Valid @RequestBody NoticeRequest request) {
        return ApiResponse.success(AdminNotice.from(notices.register(request.title(), request.contentUrl())));
    }

    /** 제목·원문 URL 전체 교체. 노출 상태는 visibility 경로가 따로 바꾼다. */
    @PutMapping(value = "/notices/{noticeId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<AdminNotice> editNotice(@PathVariable long noticeId, @Valid @RequestBody NoticeRequest request) {
        return ApiResponse.success(AdminNotice.from(notices.edit(noticeId, request.title(), request.contentUrl())));
    }

    /** 숨김(hidden=true)이 삭제 역할이다 — hard delete 경로는 두지 않는다. */
    @PutMapping(value = "/notices/{noticeId}/visibility", consumes = MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<AdminNotice> changeNoticeVisibility(@PathVariable long noticeId,
                                                    @Valid @RequestBody VisibilityRequest request) {
        return ApiResponse.success(AdminNotice.from(notices.changeVisibility(noticeId, request.hidden())));
    }

    /** 전체 문의, 최신 순. 본문·email이 실리므로 access log는 privacy skeleton 대상이다(#518). */
    @GetMapping("/inquiries")
    ApiResponse<List<AdminInquiry>> inquiries() {
        return ApiResponse.success(inquiries.findAll().stream()
                .map(item -> AdminInquiry.from(item.inquiry(), item.attachmentFilenames().size())).toList());
    }

    /** 상세 + 첨부 열람 URL(presigned GET, 유효시간 내). */
    @GetMapping("/inquiries/{inquiryId}")
    ApiResponse<AdminInquiryDetail> inquiry(@PathVariable long inquiryId) {
        InquiryService.InquiryWithAttachments item = inquiries.get(inquiryId);
        List<AdminInquiryAttachment> attachments = item.attachmentFilenames().stream()
                .map(filename -> new AdminInquiryAttachment(filename,
                        inquiryAttachments.viewUrl(item.inquiry().getSubjectId(), filename)))
                .toList();
        return ApiResponse.success(new AdminInquiryDetail(AdminInquiry.from(item.inquiry(), attachments.size()),
                attachments));
    }

    /** 답장을 보낸 뒤 처리됨 표시(true) 또는 해제(false). 서버는 email을 보내지 않는다. */
    @PutMapping(value = "/inquiries/{inquiryId}/answered", consumes = MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<AdminInquiry> changeInquiryAnswered(@PathVariable long inquiryId,
                                                    @Valid @RequestBody AnsweredRequest request) {
        Inquiry inquiry = inquiries.changeAnswered(inquiryId, request.answered());
        return ApiResponse.success(AdminInquiry.from(inquiry, inquiries.get(inquiryId).attachmentFilenames().size()));
    }

    record PublishRequest(@NotNull TermType termType,
                          @NotBlank @Size(max = TermDocumentId.VERSION_MAX_LENGTH) String version,
                          @NotBlank @Size(max = 255) String title,
                          @NotBlank @Size(max = 512) String contentUrl,
                          @NotNull @AssertTrue Boolean publicationConfirmed) { }

    record VersionRequest(@NotNull @Positive Long minAppVersion,
                          @NotNull @Positive Long recommendAppVersion) {
        @JsonCreator
        static VersionRequest from(@JsonProperty("minAppVersion") JsonNode minimum,
                                   @JsonProperty("recommendAppVersion") JsonNode recommended) {
            return new VersionRequest(integer(minimum), integer(recommended));
        }

        private static Long integer(JsonNode value) {
            if (value == null || value.isNull()) return null;
            // Jackson의 기본 float→Long 절삭으로 의도와 다른 버전을 저장하지 않는다.
            if (!value.isIntegralNumber() || !value.canConvertToLong()) {
                throw new IllegalArgumentException("App version must be a JSON Long integer");
            }
            return value.longValue();
        }
    }

    record Document(TermType termType, String version, String title, String contentUrl) {
        static Document from(TermDocument doc) {
            return new Document(doc.getTermType(), doc.getVersion(), doc.getTitle(), doc.getContentUrl());
        }
    }

    record TermGroup(TermType termType, Document current, List<Document> documents) { }
    record Publication(Document saved, Document current) { }

    record NoticeRequest(@NotBlank @Size(max = Notice.TITLE_MAX_LENGTH) String title,
                         @NotBlank @Size(max = Notice.CONTENT_URL_MAX_LENGTH) String contentUrl) { }

    record VisibilityRequest(@NotNull Boolean hidden) { }

    record AdminNotice(Long noticeId, String title, String contentUrl, boolean hidden,
                       LocalDateTime createdAt, LocalDateTime updatedAt) {
        static AdminNotice from(Notice notice) {
            return new AdminNotice(notice.getNoticeId(), notice.getTitle(), notice.getContentUrl(),
                    notice.isHidden(), notice.getCreatedAt(), notice.getUpdatedAt());
        }
    }

    record AnsweredRequest(@NotNull Boolean answered) { }

    /** subject·channel은 싣지 않는다 — 관리자가 회신에 필요한 것은 분류·주소·본문·처리 여부뿐이다. */
    record AdminInquiry(Long inquiryId, InquiryCategory category, String email, String body, int attachmentCount,
                        LocalDateTime answeredAt, LocalDateTime createdAt) {
        static AdminInquiry from(Inquiry inquiry, int attachmentCount) {
            return new AdminInquiry(inquiry.getInquiryId(), inquiry.getCategory(), inquiry.getEmail(),
                    inquiry.getBody(), attachmentCount, inquiry.getAnsweredAt(), inquiry.getCreatedAt());
        }
    }

    record AdminInquiryAttachment(String filename, String viewUrl) { }
    record AdminInquiryDetail(AdminInquiry inquiry, List<AdminInquiryAttachment> attachments) { }
}
