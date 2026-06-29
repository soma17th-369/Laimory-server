package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.laimory.server.timeline.dto.PhotoUploadCreateResponse;
import com.laimory.server.timeline.dto.PhotoUploadItem;
import com.laimory.server.timeline.photo.S3PhotoStorageService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.util.unit.DataSize;

/**
 * presign 발급 오케스트레이터 단위 테스트. S3 어댑터는 Mockito mock(네트워크 0).
 * 한도(개수/크기/총합)·타입 검증과 content-length 바인딩(size→presign 인자)을 고정한다.
 */
class PhotoUploadServiceTest {

    private static final long TEN_MB = 10L * 1024 * 1024;

    private S3PhotoStorageService storage;
    private PhotoUploadService service;

    @BeforeEach
    void setUp() {
        storage = Mockito.mock(S3PhotoStorageService.class);
        // maxCount=3, per-photo=10MB, total=20MB
        service = new PhotoUploadService(storage, 3, DataSize.ofBytes(TEN_MB), DataSize.ofBytes(2 * TEN_MB));
    }

    @Test
    void createUploads_issuesFilenameAndUrlPerPhoto() {
        when(storage.generatePresignedPutUrl(anyString(), anyString(), anyLong()))
                .thenReturn("https://example/put");

        PhotoUploadCreateResponse response = service.createUploads("v1", List.of(
                new PhotoUploadItem("image/jpeg", 1000L),
                new PhotoUploadItem("image/png", 2000L)));

        assertThat(response.uploads()).hasSize(2);
        assertThat(response.uploads().get(0).filename()).matches("^[0-9a-f-]{36}\\.jpg$");
        assertThat(response.uploads().get(1).filename()).matches("^[0-9a-f-]{36}\\.png$");
        assertThat(response.uploads().get(0).uploadUrl()).isEqualTo("https://example/put");
    }

    @Test
    void createUploads_bindsSizeAsContentLength() {
        when(storage.generatePresignedPutUrl(anyString(), anyString(), anyLong()))
                .thenReturn("https://example/put");

        service.createUploads("v1", List.of(new PhotoUploadItem("image/jpeg", 4242L)));

        ArgumentCaptor<Long> length = ArgumentCaptor.forClass(Long.class);
        verify(storage).generatePresignedPutUrl(anyString(), eq("image/jpeg"), length.capture());
        assertThat(length.getValue()).isEqualTo(4242L);
    }

    @Test
    void createUploads_fullKeyEmbedsFilenameUnderPhotosPrefix() {
        when(storage.generatePresignedPutUrl(anyString(), anyString(), anyLong()))
                .thenReturn("https://example/put");

        PhotoUploadCreateResponse response =
                service.createUploads("v1", List.of(new PhotoUploadItem("image/jpeg", 1000L)));

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(storage).generatePresignedPutUrl(key.capture(), anyString(), anyLong());
        assertThat(key.getValue()).endsWith("/photos/" + response.uploads().get(0).filename());
    }

    @Test
    void createUploads_rejectsEmptyPhotos() {
        assertThatThrownBy(() -> service.createUploads("v1", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.createUploads("v1", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createUploads_rejectsTooManyPhotos() {
        assertThatThrownBy(() -> service.createUploads("v1", List.of(
                new PhotoUploadItem("image/jpeg", 1L),
                new PhotoUploadItem("image/jpeg", 1L),
                new PhotoUploadItem("image/jpeg", 1L),
                new PhotoUploadItem("image/jpeg", 1L))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createUploads_rejectsNullOrNonPositiveSize() {
        assertThatThrownBy(() -> service.createUploads("v1", List.of(new PhotoUploadItem("image/jpeg", null))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.createUploads("v1", List.of(new PhotoUploadItem("image/jpeg", 0L))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createUploads_rejectsPerPhotoOverLimit() {
        assertThatThrownBy(() -> service.createUploads("v1",
                List.of(new PhotoUploadItem("image/jpeg", TEN_MB + 1))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createUploads_rejectsTotalOverLimit() {
        // 각 사진은 per-photo 한도(10MB) 이하지만 합이 total 한도(20MB) 초과.
        assertThatThrownBy(() -> service.createUploads("v1", List.of(
                new PhotoUploadItem("image/jpeg", TEN_MB),
                new PhotoUploadItem("image/jpeg", TEN_MB),
                new PhotoUploadItem("image/jpeg", 1L))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createUploads_rejectsUnsupportedContentType() {
        assertThatThrownBy(() -> service.createUploads("v1",
                List.of(new PhotoUploadItem("image/gif", 1000L))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.createUploads("v1",
                List.of(new PhotoUploadItem("  ", 1000L))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
