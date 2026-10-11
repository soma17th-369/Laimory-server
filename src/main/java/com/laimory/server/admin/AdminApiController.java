package com.laimory.server.admin;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.laimory.server.appconfig.AppConfigResponse;
import com.laimory.server.appconfig.AppConfigService;
import com.laimory.server.common.ApiResponse;
import com.laimory.server.inquiry.dto.AdminInquiryDetailResponse;
import com.laimory.server.inquiry.dto.AdminInquiryResponse;
import com.laimory.server.inquiry.service.InquiryService;
import com.laimory.server.notice.dto.AdminNoticeResponse;
import com.laimory.server.notice.dto.NoticeThumbnailUploadResponse;
import com.laimory.server.notice.entity.Notice;
import com.laimory.server.notice.service.NoticeService;
import com.laimory.server.notice.service.NoticeThumbnailService;
import com.laimory.server.terms.TermType;
import com.laimory.server.terms.dto.AdminTermGroupResponse;
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

/**
 * localhost 관리자 웹 API. 쓰기는 결과를 반환하지 않는다({@code body: null}) — 관리자 웹이 쓰기 직후 목록을
 * 다시 조회하므로 조회가 단일 원천이다(#528).
 */
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
    private final NoticeThumbnailService noticeThumbnails;
    private final InquiryService inquiries;

    @GetMapping("/terms")
    ApiResponse<List<AdminTermGroupResponse>> terms() {
        return ApiResponse.success(documents.findAllTermGroups());
    }

    @PostMapping(value = "/terms", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<Void> publish(@Valid @RequestBody PublishRequest request) {
        registrations.register(request.termType(), request.version(), request.title(), request.contentUrl(),
                request.publicationConfirmed());
        return ApiResponse.success(null);
    }

    @GetMapping("/app-config")
    ApiResponse<AppConfigResponse> appConfig() {
        return ApiResponse.success(appConfig.getAppConfig("v1"));
    }

    @PutMapping(value = "/app-config", consumes = MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<Void> updateConfig(@Valid @RequestBody VersionRequest request) {
        appConfig.updateVersions(request.minAppVersion(), request.recommendAppVersion());
        return ApiResponse.success(null);
    }

    /** 숨김 포함 전체 공지, 최신 순 — 공개 목록과 달리 노출 상태를 함께 보여준다. */
    @GetMapping("/notices")
    ApiResponse<List<AdminNoticeResponse>> notices() {
        return ApiResponse.success(notices.findAllNotices());
    }

    @PostMapping(value = "/notices", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<Void> registerNotice(@Valid @RequestBody NoticeRequest request) {
        notices.register(request.title(), request.contentUrl());
        return ApiResponse.success(null);
    }

    /** 제목·원문 URL 전체 교체. 노출 상태는 visibility 경로가 따로 바꾼다. */
    @PutMapping(value = "/notices/{noticeId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<Void> editNotice(@PathVariable long noticeId, @Valid @RequestBody NoticeRequest request) {
        notices.edit(noticeId, request.title(), request.contentUrl());
        return ApiResponse.success(null);
    }

    /** 숨김(hidden=true)이 삭제 역할이다 — hard delete 경로는 두지 않는다. */
    @PutMapping(value = "/notices/{noticeId}/visibility", consumes = MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<Void> changeNoticeVisibility(@PathVariable long noticeId, @Valid @RequestBody VisibilityRequest request) {
        notices.changeVisibility(noticeId, request.hidden());
        return ApiResponse.success(null);
    }

    /** 앱 시작 팝업 지정(true)·해제(false) — 이 공지만 바꾸며 여러 건을 동시에 지정할 수 있다(#553). */
    @PutMapping(value = "/notices/{noticeId}/popup", consumes = MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<Void> changeNoticePopup(@PathVariable long noticeId, @Valid @RequestBody PopupRequest request) {
        notices.changePopup(noticeId, request.popup());
        return ApiResponse.success(null);
    }

    /**
     * 썸네일 업로드 URL 발급(#560) — 관리자 웹은 이 URL로 S3에 직접 PUT하고(사진 bucket CORS가 관리자 origin을
     * 허용) 성공한 뒤에만 filename을 저장한다. 발급 결과가 곧 응답이라 쓰기 void 규칙(#528)의 대상이 아니다.
     */
    @PostMapping(value = "/notices/thumbnail-uploads", consumes = MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<NoticeThumbnailUploadResponse> createNoticeThumbnailUpload(
            @Valid @RequestBody ThumbnailUploadRequest request) {
        return ApiResponse.success(noticeThumbnails.createUpload(request.contentType(), request.size()));
    }

    /** 썸네일 교체(제거 없음) — 팝업 지정은 썸네일이 있어야 한다. */
    @PutMapping(value = "/notices/{noticeId}/thumbnail", consumes = MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<Void> changeNoticeThumbnail(@PathVariable long noticeId, @Valid @RequestBody ThumbnailRequest request) {
        notices.changeThumbnail(noticeId, request.filename());
        return ApiResponse.success(null);
    }

    /** 전체 문의, 최신 순. 제목·내용·email이 실리므로 access log는 privacy skeleton 대상이다(#518). */
    @GetMapping("/inquiries")
    ApiResponse<List<AdminInquiryResponse>> inquiries() {
        return ApiResponse.success(inquiries.findAll());
    }

    /** 상세 + 첨부 열람 URL(앱 소유자와 같은 무서명 CDN URL, #529). */
    @GetMapping("/inquiries/{inquiryId}")
    ApiResponse<AdminInquiryDetailResponse> inquiry(@PathVariable long inquiryId) {
        return ApiResponse.success(inquiries.get(inquiryId));
    }

    /** 답장을 보낸 뒤 처리됨 표시(true) 또는 해제(false). 서버는 email을 보내지 않는다. */
    @PutMapping(value = "/inquiries/{inquiryId}/answered", consumes = MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<Void> changeInquiryAnswered(@PathVariable long inquiryId, @Valid @RequestBody AnsweredRequest request) {
        inquiries.changeAnswered(inquiryId, request.answered());
        return ApiResponse.success(null);
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

    record NoticeRequest(@NotBlank @Size(max = Notice.TITLE_MAX_LENGTH) String title,
                         @NotBlank @Size(max = Notice.CONTENT_URL_MAX_LENGTH) String contentUrl) { }

    record VisibilityRequest(@NotNull Boolean hidden) { }

    record PopupRequest(@NotNull Boolean popup) { }

    record ThumbnailUploadRequest(@NotBlank String contentType, @NotNull @Positive Long size) { }

    record ThumbnailRequest(@NotBlank String filename) { }

    record AnsweredRequest(@NotNull Boolean answered) { }
}
