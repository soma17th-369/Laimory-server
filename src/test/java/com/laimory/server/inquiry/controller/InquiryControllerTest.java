package com.laimory.server.inquiry.controller;

import static com.laimory.server.testsupport.AuthTestSupport.authenticatedUser;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.config.SecurityConfig;
import com.laimory.server.inquiry.dto.InquiryAttachmentUploadCreateResponse;
import com.laimory.server.inquiry.dto.InquiryAttachmentUploadItem;
import com.laimory.server.inquiry.dto.InquiryAttachmentUploadResponse;
import com.laimory.server.inquiry.entity.Inquiry;
import com.laimory.server.inquiry.service.InquiryAttachmentService;
import com.laimory.server.inquiry.service.InquiryService;
import com.laimory.server.testsupport.AuthTestSupport;
import com.laimory.server.testsupport.TestSubjects;
import com.laimory.server.user.service.SubjectMappingService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 문의 컨트롤러 슬라이스(MockMvc) — 인증 게이트(401), 접수 201/body=null envelope, Bean Validation 400,
 * 첨부 presign 발급 200과 한도 초과 -1004, 내 문의 목록·상세의 응답 모양과 404·400 wire 계약을 검증한다.
 * 서비스는 mock이라 owner 필터·정렬·상한은 {@code InquiryPersistenceIntegrationTest}가 소유한다. 인프라 0.
 */
@WebMvcTest(InquiryController.class)
@Import({SecurityConfig.class, AuthTestSupport.JwtTokensTestConfig.class})
class InquiryControllerTest {

    private static final long USER_ID = 7L;
    private static final UUID SUBJECT_ID = TestSubjects.id(USER_ID);
    private static final String INQUIRIES = "/a/api/v1/inquiries";
    private static final String UPLOADS = INQUIRIES + "/attachment-uploads";
    private static final String FILENAME = "0199a1b2-c3d4-7e5f-8a90-b1c2d3e4f5a6.jpg";
    private static final String FILENAME_B = "0199a1b2-c3d4-7e5f-8a90-b1c2d3e4f5a7.png";
    private static final String VALID_BODY = "{\"email\":\"user@example.com\","
            + "\"title\":\"앱이 멈춰요\",\"description\":\"사진 올리면 멈춰요\","
            + "\"attachmentFilenames\":[\"" + FILENAME + "\"]}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private InquiryService inquiryService;
    @MockitoBean
    private InquiryAttachmentService inquiryAttachmentService;
    @MockitoBean
    private SubjectMappingService subjectMappingService;

    @BeforeEach
    void resolveSubject() {
        when(subjectMappingService.getRequired(USER_ID)).thenReturn(SUBJECT_ID);
    }

    @Test
    void unauthenticatedRequestsAreRejected401BeforeService() throws Exception {
        mockMvc.perform(post(INQUIRIES).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.header.code").value(-2001));
        mockMvc.perform(post(UPLOADS).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attachments\":[{\"contentType\":\"image/jpeg\",\"size\":10}]}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.header.code").value(-2001));

        verifyNoInteractions(inquiryService, inquiryAttachmentService);
    }

    @Test
    void createInquiryReturns201WithNullBodyAndPassesSubjectAndFields() throws Exception {
        mockMvc.perform(post(INQUIRIES).with(authenticatedUser(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Transaction-Id"))
                .andExpect(jsonPath("$.header.code").value(0))
                .andExpect(jsonPath("$.body").doesNotExist());

        verify(inquiryService).register("v1", SUBJECT_ID, "user@example.com", "앱이 멈춰요",
                "사진 올리면 멈춰요", List.of(FILENAME));
    }

    @Test
    void createInquiryWithoutAttachmentsPassesNullList() throws Exception {
        mockMvc.perform(post(INQUIRIES).with(authenticatedUser(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"user@example.com\",\"title\":\"문의\",\"description\":\"내용\"}"))
                .andExpect(status().isCreated());

        verify(inquiryService).register("v1", SUBJECT_ID, "user@example.com", "문의", "내용", null);
    }

    @Test
    void createInquiryRejectsInvalidEmailMissingOrBlankTitleAndDescriptionAndLegacyBody() throws Exception {
        // 첨부 개수 초과는 경계가 아니라 서비스 검증(-1004)이다 — 아래 service 매핑 테스트가 소유한다.
        for (String bad : List.of(
                "{\"title\":\"문의\",\"description\":\"내용\"}",
                "{\"email\":\"not-an-email\",\"title\":\"문의\",\"description\":\"내용\"}",
                "{\"email\":\"user@example.com\",\"description\":\"내용\"}",
                "{\"email\":\"user@example.com\",\"title\":\"   \",\"description\":\"내용\"}",
                "{\"email\":\"user@example.com\",\"title\":\"문의\",\"description\":\"   \"}",
                "{\"email\":\"user@example.com\",\"body\":\"구 필드\"}")) {
            mockMvc.perform(post(INQUIRIES).with(authenticatedUser(USER_ID))
                            .contentType(MediaType.APPLICATION_JSON).content(bad))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.header.code").value(-400));
        }

        verifyNoInteractions(inquiryService);
    }

    @Test
    void createInquiryPassesTitleOverLimitOnlyBySurroundingSpacesToService() throws Exception {
        // 경계는 제목 길이를 세지 않는다 — 앞뒤 공백 제거 후 길이는 Inquiry.of가 검사한다.
        String paddedTitle = " " + "제".repeat(100) + " ";

        mockMvc.perform(post(INQUIRIES).with(authenticatedUser(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"user@example.com\",\"title\":\"" + paddedTitle
                                + "\",\"description\":\"내용\"}"))
                .andExpect(status().isCreated());

        verify(inquiryService).register("v1", SUBJECT_ID, "user@example.com", paddedTitle, "내용", null);
    }

    @Test
    void createInquiryMapsServiceValidationFailuresToErrorCodes() throws Exception {
        when(inquiryService.register(any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("attachmentFilenames[0] must be a presigned filename"))
                .thenThrow(new BusinessException(ExceptionType.PHOTO_COUNT_EXCEEDED, 3));

        mockMvc.perform(post(INQUIRIES).with(authenticatedUser(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.code").value(-400));
        mockMvc.perform(post(INQUIRIES).with(authenticatedUser(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.code").value(-1004));
    }

    @Test
    void createAttachmentUploadsReturnsFilenamesAndUrlsInOrder() throws Exception {
        when(inquiryAttachmentService.createUploads(eq("v1"), eq(SUBJECT_ID),
                eq(List.of(new InquiryAttachmentUploadItem("image/jpeg", 1024L)))))
                .thenReturn(new InquiryAttachmentUploadCreateResponse(List.of(
                        new InquiryAttachmentUploadResponse(FILENAME, "https://s3.example/put"))));

        mockMvc.perform(post(UPLOADS).with(authenticatedUser(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attachments\":[{\"contentType\":\"image/jpeg\",\"size\":1024}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.code").value(0))
                .andExpect(jsonPath("$.body.uploads[0].filename").value(FILENAME))
                .andExpect(jsonPath("$.body.uploads[0].uploadUrl").value("https://s3.example/put"));
    }

    @Test
    void createAttachmentUploadsOverLimitReturns1004() throws Exception {
        when(inquiryAttachmentService.createUploads(any(), any(), any()))
                .thenThrow(new BusinessException(ExceptionType.PHOTO_COUNT_EXCEEDED, 3));

        mockMvc.perform(post(UPLOADS).with(authenticatedUser(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attachments\":[{\"contentType\":\"image/jpeg\",\"size\":1024}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.code").value(-1004))
                .andExpect(jsonPath("$.body").doesNotExist());
    }

    @Test
    void myInquiryReadsRejectUnauthenticated401BeforeService() throws Exception {
        mockMvc.perform(get(INQUIRIES))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.header.code").value(-2001));
        mockMvc.perform(get(INQUIRIES + "/12"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.header.code").value(-2001));

        verifyNoInteractions(inquiryService, inquiryAttachmentService);
    }

    @Test
    void myInquiriesListCarriesTitleStatusAndTimesOnly() throws Exception {
        Inquiry answered = inquiry(12L, LocalDateTime.of(2026, 9, 30, 14, 0));
        Inquiry received = inquiry(7L, null);
        when(inquiryService.findMine("v1", SUBJECT_ID)).thenReturn(List.of(answered, received));

        mockMvc.perform(get(INQUIRIES).with(authenticatedUser(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.code").value(0))
                .andExpect(jsonPath("$.body.inquiries.length()").value(2))
                .andExpect(jsonPath("$.body.inquiries[0].inquiryId").value(12))
                .andExpect(jsonPath("$.body.inquiries[0].title").value("앱이 멈춰요"))
                .andExpect(jsonPath("$.body.inquiries[0].status").value("ANSWERED"))
                .andExpect(jsonPath("$.body.inquiries[0].createdAt").value("2026-09-29T10:00:00"))
                .andExpect(jsonPath("$.body.inquiries[0].answeredAt").value("2026-09-30T14:00:00"))
                .andExpect(jsonPath("$.body.inquiries[1].inquiryId").value(7))
                .andExpect(jsonPath("$.body.inquiries[1].status").value("RECEIVED"))
                .andExpect(jsonPath("$.body.inquiries[1].answeredAt").isEmpty())
                .andExpect(jsonPath("$.body.inquiries[0].description").doesNotExist())
                .andExpect(jsonPath("$.body.inquiries[0].email").doesNotExist())
                .andExpect(jsonPath("$.body.inquiries[0].attachmentUrls").doesNotExist())
                .andExpect(jsonPath("$.body.inquiries[0].attachmentCount").doesNotExist());
    }

    @Test
    void myInquiriesListIsEmptyArrayWhenNone() throws Exception {
        when(inquiryService.findMine("v1", SUBJECT_ID)).thenReturn(List.of());

        mockMvc.perform(get(INQUIRIES).with(authenticatedUser(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.inquiries").isArray())
                .andExpect(jsonPath("$.body.inquiries").isEmpty());
    }

    @Test
    void myInquiryDetailIsFlatWithAttachmentUrlsAsOrderedStrings() throws Exception {
        when(inquiryService.getMine("v1", SUBJECT_ID, 12L)).thenReturn(new InquiryService.InquiryWithAttachments(
                inquiry(12L, null), List.of(FILENAME_B, FILENAME)));
        when(inquiryAttachmentService.cdnUrl(SUBJECT_ID, FILENAME_B)).thenReturn("https://cdn.example/b.png");
        when(inquiryAttachmentService.cdnUrl(SUBJECT_ID, FILENAME)).thenReturn("https://cdn.example/a.jpg");

        mockMvc.perform(get(INQUIRIES + "/12").with(authenticatedUser(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.code").value(0))
                .andExpect(jsonPath("$.body.inquiryId").value(12))
                .andExpect(jsonPath("$.body.title").value("앱이 멈춰요"))
                .andExpect(jsonPath("$.body.status").value("RECEIVED"))
                .andExpect(jsonPath("$.body.email").value("user@example.com"))
                .andExpect(jsonPath("$.body.description").value("사진 올리면 멈춰요"))
                .andExpect(jsonPath("$.body.createdAt").value("2026-09-29T10:00:00"))
                .andExpect(jsonPath("$.body.answeredAt").isEmpty())
                .andExpect(jsonPath("$.body.attachmentUrls.length()").value(2))
                .andExpect(jsonPath("$.body.attachmentUrls[0]").value("https://cdn.example/b.png"))
                .andExpect(jsonPath("$.body.attachmentUrls[1]").value("https://cdn.example/a.jpg"))
                // 관리자 상세의 {inquiry, attachments:[{filename, viewUrl}]} 모양이 아니다.
                .andExpect(jsonPath("$.body.inquiry").doesNotExist())
                .andExpect(jsonPath("$.body.attachments").doesNotExist())
                .andExpect(jsonPath("$..filename").isEmpty());
    }

    @Test
    void myInquiryDetailWithoutAttachmentsHasEmptyUrlArray() throws Exception {
        when(inquiryService.getMine("v1", SUBJECT_ID, 12L)).thenReturn(new InquiryService.InquiryWithAttachments(
                inquiry(12L, LocalDateTime.of(2026, 9, 30, 14, 0)), List.of()));

        mockMvc.perform(get(INQUIRIES + "/12").with(authenticatedUser(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.status").value("ANSWERED"))
                .andExpect(jsonPath("$.body.attachmentUrls").isArray())
                .andExpect(jsonPath("$.body.attachmentUrls").isEmpty());
    }

    @Test
    void myInquiryDetailMissingOrForeignIs404AndNonNumericIdIs400() throws Exception {
        when(inquiryService.getMine("v1", SUBJECT_ID, 99L))
                .thenThrow(new BusinessException(ExceptionType.RESOURCE_NOT_FOUND));

        mockMvc.perform(get(INQUIRIES + "/99").with(authenticatedUser(USER_ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.code").value(-404))
                .andExpect(jsonPath("$.body").doesNotExist());
        mockMvc.perform(get(INQUIRIES + "/abc").with(authenticatedUser(USER_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.code").value(-400));
    }

    private static Inquiry inquiry(long inquiryId, LocalDateTime answeredAt) {
        Inquiry inquiry = Inquiry.of(SUBJECT_ID, "user@example.com", "앱이 멈춰요", "사진 올리면 멈춰요");
        ReflectionTestUtils.setField(inquiry, "inquiryId", inquiryId);
        ReflectionTestUtils.setField(inquiry, "createdAt", LocalDateTime.of(2026, 9, 29, 10, 0));
        inquiry.changeAnswered(answeredAt);
        return inquiry;
    }
}
