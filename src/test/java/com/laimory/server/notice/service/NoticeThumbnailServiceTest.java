package com.laimory.server.notice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.notice.dto.NoticeThumbnailUploadResponse;
import com.laimory.server.timeline.photo.PhotoFilenames;
import com.laimory.server.timeline.photo.S3PhotoStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.util.unit.DataSize;

/**
 * 공지 썸네일 S3 경계 — 발급 key가 subject namespace 밖 {@code notices/} prefix인지, 사진과 같은 타입·크기 규칙을
 * 쓰는지, CDN URL이 같은 key를 가리키는지 검증한다. S3 presigner만 외부 경계라 mock이다.
 */
@ExtendWith(MockitoExtension.class)
class NoticeThumbnailServiceTest {

    @Mock
    private S3PhotoStorageService s3PhotoStorageService;

    private NoticeThumbnailService service;

    @BeforeEach
    void setUp() {
        service = new NoticeThumbnailService(s3PhotoStorageService, DataSize.ofMegabytes(10), "cdn.example");
    }

    @Test
    void createUploadSignsNewFilenameUnderNoticesPrefixWithDeclaredTypeAndSize() {
        when(s3PhotoStorageService.generatePresignedPutUrl(anyString(), eq("image/png"), eq(2048L)))
                .thenAnswer(invocation -> "https://s3.example/" + invocation.getArgument(0) + "?signed");

        NoticeThumbnailUploadResponse upload = service.createUpload("image/png", 2048L);

        assertThat(PhotoFilenames.isValid(upload.filename())).isTrue();
        assertThat(upload.filename()).endsWith(".png");
        assertThat(upload.uploadUrl()).isEqualTo("https://s3.example/notices/" + upload.filename() + "?signed");
    }

    @Test
    void createUploadRejectsUnsupportedTypeWithoutSigning() {
        assertThatThrownBy(() -> service.createUpload("image/gif", 2048L))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getExceptionType())
                .isEqualTo(ExceptionType.UNSUPPORTED_PHOTO_FORMAT);
        verify(s3PhotoStorageService, never()).generatePresignedPutUrl(anyString(), anyString(), anyLong());
    }

    @Test
    void createUploadRejectsSizeOverPhotoLimitWithoutSigning() {
        long overLimit = DataSize.ofMegabytes(10).toBytes() + 1;

        assertThatThrownBy(() -> service.createUpload("image/jpeg", overLimit))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getExceptionType())
                .isEqualTo(ExceptionType.PHOTO_SIZE_EXCEEDED);
        verify(s3PhotoStorageService, never()).generatePresignedPutUrl(anyString(), anyString(), anyLong());
    }

    @Test
    void cdnUrlPointsToNoticesPrefixOnPhotoCdn() {
        assertThat(service.cdnUrl("0199a1b2-c3d4-7e5f-8a90-b1c2d3e4f5a6.webp"))
                .isEqualTo("https://cdn.example/notices/0199a1b2-c3d4-7e5f-8a90-b1c2d3e4f5a6.webp");
    }
}
