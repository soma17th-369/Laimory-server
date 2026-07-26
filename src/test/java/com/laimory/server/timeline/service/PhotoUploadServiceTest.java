package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.timeline.dto.PhotoUploadCreateResponse;
import com.laimory.server.timeline.dto.PhotoUploadItem;
import com.laimory.server.timeline.photo.PhotoObjectKeys;
import com.laimory.server.timeline.photo.S3PhotoStorageService;
import java.util.Arrays;
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
    private static final long USER_ID = 424242L;

    private S3PhotoStorageService storage;
    private PhotoUploadService service;

    @BeforeEach
    void setUp() {
        storage = Mockito.mock(S3PhotoStorageService.class);
        // maxCount=3, per-photo=10MB (총합 캡 없음 — 개수x장당으로 유계)
        service = new PhotoUploadService(storage, 3, DataSize.ofBytes(TEN_MB));
    }

    @Test
    void createUploads_issuesFilenameAndUrlPerPhoto() {
        when(storage.generatePresignedPutUrl(anyString(), anyString(), anyLong()))
                .thenReturn("https://example/put");

        PhotoUploadCreateResponse response = service.createUploads("v1", USER_ID, List.of(
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

        service.createUploads("v1", USER_ID, List.of(new PhotoUploadItem("image/jpeg", 4242L)));

        ArgumentCaptor<Long> length = ArgumentCaptor.forClass(Long.class);
        verify(storage).generatePresignedPutUrl(anyString(), eq("image/jpeg"), length.capture());
        assertThat(length.getValue()).isEqualTo(4242L);
    }

    @Test
    void createUploads_fullKeyUsesCallerUserIdHashNamespace() {
        // full key의 hash namespace는 호출자(인증 principal) userId에서 파생된다 — 고정 0 fallback 회귀 방지.
        when(storage.generatePresignedPutUrl(anyString(), anyString(), anyLong()))
                .thenReturn("https://example/put");

        PhotoUploadCreateResponse response =
                service.createUploads("v1", USER_ID, List.of(new PhotoUploadItem("image/jpeg", 1000L)));

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(storage).generatePresignedPutUrl(key.capture(), anyString(), anyLong());
        assertThat(key.getValue())
                .isEqualTo(PhotoObjectKeys.sha256hex(USER_ID) + "/photos/" + response.uploads().get(0).filename());
        assertThat(key.getValue()).doesNotStartWith(PhotoObjectKeys.sha256hex(0L));
    }

    @Test
    void createUploads_rejectsEmptyPhotos() {
        assertThatThrownBy(() -> service.createUploads("v1", USER_ID, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.createUploads("v1", USER_ID, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createUploads_rejectsTooManyPhotos_withDedicatedCode() {
        assertThatThrownBy(() -> service.createUploads("v1", USER_ID, List.of(
                new PhotoUploadItem("image/jpeg", 1L),
                new PhotoUploadItem("image/jpeg", 1L),
                new PhotoUploadItem("image/jpeg", 1L),
                new PhotoUploadItem("image/jpeg", 1L))))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(-1004);
                    assertThat(ex.getArgs()).containsExactly(3); // 메시지 {0} = 한도값
                });
    }

    @Test
    void createUploads_rejectsNullElement() {
        assertThatThrownBy(() -> service.createUploads("v1", USER_ID,
                Arrays.asList(new PhotoUploadItem("image/jpeg", 1L), null)))
                .isInstanceOf(IllegalArgumentException.class); // NPE→500이 아니라 400
    }

    @Test
    void createUploads_rejectsNullOrNonPositiveSize() {
        assertThatThrownBy(() -> service.createUploads("v1", USER_ID, List.of(new PhotoUploadItem("image/jpeg", null))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.createUploads("v1", USER_ID, List.of(new PhotoUploadItem("image/jpeg", 0L))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createUploads_rejectsPerPhotoOverLimit_withDedicatedCode() {
        assertThatThrownBy(() -> service.createUploads("v1", USER_ID,
                List.of(new PhotoUploadItem("image/jpeg", TEN_MB + 1))))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(-1005);
                    assertThat(ex.getArgs()).containsExactly(10L); // MB 표기(바이트 아님)
                });
    }

    @Test
    void createUploads_allowsFullSelectionOfMaxSizePhotos() {
        // 총합 캡 없음: 장당·개수 한도를 지키는 선택은 합계와 무관하게 통과한다(정상 선택 거절 엣지 제거).
        when(storage.generatePresignedPutUrl(anyString(), anyString(), anyLong()))
                .thenReturn("https://example/put");

        PhotoUploadCreateResponse response = service.createUploads("v1", USER_ID, List.of(
                new PhotoUploadItem("image/jpeg", TEN_MB),
                new PhotoUploadItem("image/jpeg", TEN_MB),
                new PhotoUploadItem("image/jpeg", TEN_MB)));

        assertThat(response.uploads()).hasSize(3);
    }

    @Test
    void createUploads_rejectsUnsupportedContentType_withDedicatedCode_withoutEchoingInput() {
        // HEIC(아이폰 기본)·GIF — 사용자가 유발 가능 → 전용 코드. args 없음(입력 echo 금지).
        assertThatThrownBy(() -> service.createUploads("v1", USER_ID,
                List.of(new PhotoUploadItem("image/heic", 1000L))))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(-1007);
                    assertThat(ex.getArgs()).isEmpty();
                });
        assertThatThrownBy(() -> service.createUploads("v1", USER_ID,
                List.of(new PhotoUploadItem("image/gif", 1000L))))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-1007));
    }

    @Test
    void createUploads_rejectsBlankContentType_asPlainValidation() {
        // 형식 불량(누락)은 전용 코드가 아니라 제네릭 400(IAE) — 정상 앱은 보낼 수 없는 요청.
        assertThatThrownBy(() -> service.createUploads("v1", USER_ID,
                List.of(new PhotoUploadItem("  ", 1000L))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
