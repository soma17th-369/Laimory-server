package com.laimory.server.inquiry.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.inquiry.InquiryObjectKeys;
import com.laimory.server.inquiry.dto.InquiryAttachmentUploadCreateResponse;
import com.laimory.server.inquiry.dto.InquiryAttachmentUploadItem;
import com.laimory.server.testsupport.TestSubjects;
import com.laimory.server.timeline.photo.S3PhotoStorageService;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.util.unit.DataSize;

/** 문의 첨부 presign 경계 — 사진과 같은 타입·크기 규칙, 문의 전용 개수 상한과 key prefix를 검증한다. */
@ExtendWith(MockitoExtension.class)
class InquiryAttachmentServiceTest {

    private static final UUID SUBJECT_ID = TestSubjects.id(18L);

    @Mock
    private S3PhotoStorageService s3PhotoStorageService;

    private InquiryAttachmentService service() {
        return new InquiryAttachmentService(s3PhotoStorageService, DataSize.ofMegabytes(5));
    }

    @Test
    void issuesUploadsInRequestOrderUnderInquiryPrefixWithSizeBoundSignature() {
        when(s3PhotoStorageService.generatePresignedPutUrl(anyString(), anyString(), anyLong()))
                .thenReturn("https://s3.example/first", "https://s3.example/second");

        InquiryAttachmentUploadCreateResponse response = service().createUploads("v1", SUBJECT_ID, List.of(
                new InquiryAttachmentUploadItem("image/jpeg", 1_024L),
                new InquiryAttachmentUploadItem("image/webp", 2_048L)));

        assertThat(response.uploads()).hasSize(2);
        assertThat(response.uploads().get(0).filename()).endsWith(".jpg");
        assertThat(response.uploads().get(0).uploadUrl()).isEqualTo("https://s3.example/first");
        assertThat(response.uploads().get(1).filename()).endsWith(".webp");
        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Long> sizes = ArgumentCaptor.forClass(Long.class);
        verify(s3PhotoStorageService, org.mockito.Mockito.times(2))
                .generatePresignedPutUrl(keys.capture(), anyString(), sizes.capture());
        assertThat(keys.getAllValues()).allSatisfy(key ->
                assertThat(key).startsWith(InquiryObjectKeys.subjectPrefix(SUBJECT_ID)).doesNotContain("/photos/"));
        assertThat(sizes.getAllValues()).containsExactly(1_024L, 2_048L);
    }

    @Test
    void rejectsMoreThanThreeAttachmentsBeforeSigning() {
        List<InquiryAttachmentUploadItem> four = Collections.nCopies(4,
                new InquiryAttachmentUploadItem("image/png", 10L));

        assertThatThrownBy(() -> service().createUploads("v1", SUBJECT_ID, four))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getExceptionType())
                .isEqualTo(ExceptionType.PHOTO_COUNT_EXCEEDED);
        verifyNoInteractions(s3PhotoStorageService);
    }

    @Test
    void rejectsUnsupportedContentTypeOversizeAndMissingSize() {
        InquiryAttachmentService service = service();

        assertThatThrownBy(() -> service.createUploads("v1", SUBJECT_ID,
                List.of(new InquiryAttachmentUploadItem("image/heic", 10L))))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getExceptionType())
                .isEqualTo(ExceptionType.UNSUPPORTED_PHOTO_FORMAT);
        assertThatThrownBy(() -> service.createUploads("v1", SUBJECT_ID,
                List.of(new InquiryAttachmentUploadItem("image/jpeg", DataSize.ofMegabytes(5).toBytes() + 1))))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getExceptionType())
                .isEqualTo(ExceptionType.PHOTO_SIZE_EXCEEDED);
        assertThatThrownBy(() -> service.createUploads("v1", SUBJECT_ID,
                List.of(new InquiryAttachmentUploadItem("image/jpeg", null))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.createUploads("v1", SUBJECT_ID, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(s3PhotoStorageService);
    }

    @Test
    void viewUrlSignsGetForTheInquiryObjectKey() {
        when(s3PhotoStorageService.generatePresignedGetUrl(InquiryObjectKeys.fullKey("a.jpg", SUBJECT_ID)))
                .thenReturn("https://s3.example/view");

        assertThat(service().viewUrl(SUBJECT_ID, "a.jpg")).isEqualTo("https://s3.example/view");
    }
}
