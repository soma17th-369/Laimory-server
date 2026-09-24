package com.laimory.server.inquiry.controller;

import static com.laimory.server.testsupport.AuthTestSupport.authenticatedUser;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
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
import com.laimory.server.inquiry.service.InquiryAttachmentService;
import com.laimory.server.inquiry.service.InquiryService;
import com.laimory.server.testsupport.AuthTestSupport;
import com.laimory.server.testsupport.TestSubjects;
import com.laimory.server.user.service.SubjectMappingService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 문의 접수 컨트롤러 슬라이스(MockMvc) — 인증 게이트(401), 접수 201/body=null envelope, Bean Validation 400,
 * 첨부 presign 발급 200과 한도 초과 -1004 wire 계약을 검증한다. 인프라 0.
 */
@WebMvcTest(InquiryController.class)
@Import({SecurityConfig.class, AuthTestSupport.JwtTokensTestConfig.class})
class InquiryControllerTest {

    private static final long USER_ID = 7L;
    private static final UUID SUBJECT_ID = TestSubjects.id(USER_ID);
    private static final String INQUIRIES = "/a/api/v1/inquiries";
    private static final String UPLOADS = INQUIRIES + "/attachment-uploads";
    private static final String FILENAME = "0199a1b2-c3d4-7e5f-8a90-b1c2d3e4f5a6.jpg";
    private static final String VALID_BODY = "{\"email\":\"user@example.com\","
            + "\"body\":\"앱이 멈춰요\",\"attachmentFilenames\":[\"" + FILENAME + "\"]}";

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
                List.of(FILENAME));
    }

    @Test
    void createInquiryWithoutAttachmentsPassesNullList() throws Exception {
        mockMvc.perform(post(INQUIRIES).with(authenticatedUser(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"user@example.com\",\"body\":\"문의\"}"))
                .andExpect(status().isCreated());

        verify(inquiryService).register("v1", SUBJECT_ID, "user@example.com", "문의", null);
    }

    @Test
    void createInquiryRejectsInvalidEmailBlankBodyAndTooManyAttachments() throws Exception {
        for (String bad : List.of(
                "{\"body\":\"문의\"}",
                "{\"email\":\"not-an-email\",\"body\":\"문의\"}",
                "{\"email\":\"user@example.com\",\"body\":\"   \"}",
                "{\"email\":\"user@example.com\",\"body\":\"문의\","
                        + "\"attachmentFilenames\":[\"a.jpg\",\"b.jpg\",\"c.jpg\",\"d.jpg\"]}")) {
            mockMvc.perform(post(INQUIRIES).with(authenticatedUser(USER_ID))
                            .contentType(MediaType.APPLICATION_JSON).content(bad))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.header.code").value(-400));
        }

        verifyNoInteractions(inquiryService);
    }

    @Test
    void createInquiryMapsServiceValidationFailuresToErrorCodes() throws Exception {
        when(inquiryService.register(any(), any(), any(), any(), any()))
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
}
