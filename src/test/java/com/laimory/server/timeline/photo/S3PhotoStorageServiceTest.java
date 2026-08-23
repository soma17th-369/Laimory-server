package com.laimory.server.timeline.photo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.laimory.server.timeline.photo.S3PhotoStorageService.BatchDeleteResult;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.DeletedObject;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.S3Error;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/**
 * S3PhotoStorageService 단위 검증. S3Presigner/S3Client를 모킹해 presign/deleteObjects
 * 인자를 캡처한다. 인프라 0(실 S3 객체 금지 — 1,001개 chunk도 mock으로 검증).
 */
@ExtendWith(MockitoExtension.class)
class S3PhotoStorageServiceTest {

    private static final String BUCKET = "test-bucket";
    private static final Duration TTL = Duration.ofMinutes(10);

    @Mock
    private S3Presigner s3Presigner;

    @Mock
    private S3Client s3Client;

    @Mock
    private PresignedPutObjectRequest presignedRequest;

    @Test
    void generatePresignedPutUrl_bindsBucketKeyContentTypeContentLengthAndDuration() throws Exception {
        S3PhotoStorageService service = new S3PhotoStorageService(s3Presigner, s3Client, BUCKET, TTL);
        String objectKey = "deadbeef/photos/a.jpg";
        String contentType = "image/jpeg";
        long contentLength = 12_345L;

        URI presignedUri = URI.create("https://test-bucket.s3.amazonaws.com/" + objectKey + "?X-Amz-Signature=abc");
        when(presignedRequest.url()).thenReturn(presignedUri.toURL());
        when(s3Presigner.presignPutObject(any(PutObjectPresignRequest.class))).thenReturn(presignedRequest);

        String url = service.generatePresignedPutUrl(objectKey, contentType, contentLength);

        assertThat(url).isEqualTo(presignedUri.toString());

        ArgumentCaptor<PutObjectPresignRequest> captor = ArgumentCaptor.forClass(PutObjectPresignRequest.class);
        verify(s3Presigner).presignPutObject(captor.capture());
        PutObjectPresignRequest req = captor.getValue();
        assertThat(req.signatureDuration()).isEqualTo(TTL);
        assertThat(req.putObjectRequest().bucket()).isEqualTo(BUCKET);
        assertThat(req.putObjectRequest().key()).isEqualTo(objectKey);
        assertThat(req.putObjectRequest().contentType()).isEqualTo(contentType);
        // contentLength가 서명에 바인딩되어 S3가 정확한 크기를 강제(크기 우회 방지)
        assertThat(req.putObjectRequest().contentLength()).isEqualTo(contentLength);
    }

    /**
     * 실제 {@link S3Presigner}(로컬 서명 연산, 네트워크 없음)로 presigned PUT URL을 발급해
     * {@code X-Amz-SignedHeaders}에 {@code content-length}/{@code content-type}/{@code host}가
     * 실제로 서명되는지 검증한다. contentLength를 PutObjectRequest에 바인딩하더라도 그 헤더가
     * 서명에 포함되지 않으면 S3가 크기를 강제하지 못하므로(보안 계약), 이 부분을 모킹이 아닌
     * 실제 서명으로 경험적으로 확인한다.
     */
    @Test
    void generatePresignedPutUrl_signsContentLengthAndContentTypeHeaders() throws Exception {
        S3Presigner realPresigner = S3Presigner.builder()
                .region(software.amazon.awssdk.regions.Region.AP_NORTHEAST_2)
                .credentialsProvider(software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(
                        software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create(
                                "AKIDEXAMPLE", "secretdummy")))
                .build();
        S3PhotoStorageService service =
                new S3PhotoStorageService(realPresigner, s3Client /* unused here */, BUCKET, TTL);

        String url = service.generatePresignedPutUrl("deadbeef/photos/a.jpg", "image/jpeg", 12_345L);

        String rawSignedHeaders = queryParam(url, "X-Amz-SignedHeaders");
        String signedHeaders = URLDecoder.decode(rawSignedHeaders, StandardCharsets.UTF_8);
        System.out.println("X-Amz-SignedHeaders (raw)     = " + rawSignedHeaders);
        System.out.println("X-Amz-SignedHeaders (decoded) = " + signedHeaders);

        assertThat(signedHeaders.split(";"))
                .contains("content-length", "content-type", "host");
    }

    /** URL의 쿼리스트링에서 주어진 key의 (디코딩되지 않은) raw 값을 추출한다. */
    private static String queryParam(String url, String key) {
        String query = URI.create(url).getRawQuery();
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String name = eq >= 0 ? pair.substring(0, eq) : pair;
            if (name.equals(key)) {
                return eq >= 0 ? pair.substring(eq + 1) : "";
            }
        }
        throw new IllegalStateException("query param not found: " + key + " in " + url);
    }

    // --- deleteAll (DeleteObjects 배치) ---

    private static List<String> keys(int count) {
        return IntStream.range(0, count).mapToObj(i -> "deadbeef/photos/" + i + ".jpg").toList();
    }

    private static List<String> requestedKeys(DeleteObjectsRequest request) {
        return request.delete().objects().stream().map(ObjectIdentifier::key).toList();
    }

    @Test
    void deleteAll_singleBatch_bindsBucketKeysVerboseModeAndPerRequestTimeouts() {
        S3PhotoStorageService service = new S3PhotoStorageService(s3Presigner, s3Client, BUCKET, TTL);
        when(s3Client.deleteObjects(any(DeleteObjectsRequest.class)))
                .thenReturn(DeleteObjectsResponse.builder()
                        .deleted(
                                DeletedObject.builder().key("deadbeef/photos/a.jpg").build(),
                                DeletedObject.builder().key("deadbeef/photos/b.jpg").build())
                        .build());

        BatchDeleteResult result =
                service.deleteAll(List.of("deadbeef/photos/a.jpg", "deadbeef/photos/b.jpg"));

        ArgumentCaptor<DeleteObjectsRequest> captor = ArgumentCaptor.forClass(DeleteObjectsRequest.class);
        verify(s3Client).deleteObjects(captor.capture());
        DeleteObjectsRequest request = captor.getValue();
        assertThat(request.bucket()).isEqualTo(BUCKET);
        assertThat(requestedKeys(request)).containsExactly("deadbeef/photos/a.jpg", "deadbeef/photos/b.jpg");
        assertThat(request.delete().quiet()).isFalse();
        // 타임아웃은 finalized/draft batch 요청 단위 override로만 조인다(전역 client 불변).
        assertThat(request.overrideConfiguration()).hasValueSatisfying(override -> {
            assertThat(override.apiCallTimeout()).contains(Duration.ofSeconds(10));
            assertThat(override.apiCallAttemptTimeout()).contains(Duration.ofSeconds(3));
        });
        assertThat(result.deletedObjectKeys())
                .containsExactly("deadbeef/photos/a.jpg", "deadbeef/photos/b.jpg");
        assertThat(result.errorCodeByObjectKey()).isEmpty();
        assertThat(result.unreportedObjectKeys()).isEmpty();
    }

    @Test
    void deleteAll_emptyKeys_doesNotCallS3() {
        S3PhotoStorageService service = new S3PhotoStorageService(s3Presigner, s3Client, BUCKET, TTL);

        BatchDeleteResult result = service.deleteAll(List.of());

        verify(s3Client, never()).deleteObjects(any(DeleteObjectsRequest.class));
        assertThat(result.deletedObjectKeys()).isEmpty();
        assertThat(result.errorCodeByObjectKey()).isEmpty();
        assertThat(result.unreportedObjectKeys()).isEmpty();
    }

    @Test
    void deleteAll_exactly1000Keys_isSingleBatch() {
        S3PhotoStorageService service = new S3PhotoStorageService(s3Presigner, s3Client, BUCKET, TTL);
        when(s3Client.deleteObjects(any(DeleteObjectsRequest.class)))
                .thenReturn(DeleteObjectsResponse.builder().build());

        service.deleteAll(keys(1_000));

        ArgumentCaptor<DeleteObjectsRequest> captor = ArgumentCaptor.forClass(DeleteObjectsRequest.class);
        verify(s3Client, times(1)).deleteObjects(captor.capture());
        assertThat(captor.getValue().delete().objects()).hasSize(1_000);
    }

    @Test
    void deleteAll_1001Keys_splitsIntoTwoSequentialBatches() {
        S3PhotoStorageService service = new S3PhotoStorageService(s3Presigner, s3Client, BUCKET, TTL);
        when(s3Client.deleteObjects(any(DeleteObjectsRequest.class)))
                .thenReturn(DeleteObjectsResponse.builder().build());
        List<String> all = keys(1_001);

        service.deleteAll(all);

        ArgumentCaptor<DeleteObjectsRequest> captor = ArgumentCaptor.forClass(DeleteObjectsRequest.class);
        verify(s3Client, times(2)).deleteObjects(captor.capture());
        // 1,000개/batch 한도로 순서 보존 분할된다(1,000 + 1).
        assertThat(requestedKeys(captor.getAllValues().get(0))).containsExactlyElementsOf(all.subList(0, 1_000));
        assertThat(requestedKeys(captor.getAllValues().get(1))).containsExactly(all.get(1_000));
    }

    @Test
    void deleteAll_duplicateKeys_sendsEachKeyOnceAndClassifiesResultOnce() {
        S3PhotoStorageService service = new S3PhotoStorageService(s3Presigner, s3Client, BUCKET, TTL);
        when(s3Client.deleteObjects(any(DeleteObjectsRequest.class)))
                .thenReturn(DeleteObjectsResponse.builder()
                        .deleted(DeletedObject.builder().key("deadbeef/photos/a.jpg").build())
                        .build());

        BatchDeleteResult result = service.deleteAll(List.of(
                "deadbeef/photos/a.jpg",
                "deadbeef/photos/a.jpg",
                "deadbeef/photos/a.jpg"));

        ArgumentCaptor<DeleteObjectsRequest> captor = ArgumentCaptor.forClass(DeleteObjectsRequest.class);
        verify(s3Client).deleteObjects(captor.capture());
        assertThat(requestedKeys(captor.getValue())).containsExactly("deadbeef/photos/a.jpg");
        assertThat(result.deletedObjectKeys()).containsExactly("deadbeef/photos/a.jpg");
    }

    @Test
    void deleteAll_distinguishesDeletedErrorAndUnreportedKeys() {
        S3PhotoStorageService service = new S3PhotoStorageService(s3Presigner, s3Client, BUCKET, TTL);
        String deletedKey = "deadbeef/photos/deleted.jpg";
        String errorKey = "deadbeef/photos/error.jpg";
        String unreportedKey = "deadbeef/photos/unreported.jpg";
        when(s3Client.deleteObjects(any(DeleteObjectsRequest.class)))
                .thenReturn(DeleteObjectsResponse.builder()
                        .deleted(DeletedObject.builder().key(deletedKey).build())
                        .errors(S3Error.builder().key(errorKey).code("InternalError").build())
                        .build());

        BatchDeleteResult result = service.deleteAll(List.of(deletedKey, errorKey, unreportedKey));

        assertThat(result.deletedObjectKeys()).containsExactly(deletedKey);
        assertThat(result.errorCodeByObjectKey()).containsExactlyEntriesOf(
                java.util.Map.of(errorKey, "InternalError"));
        assertThat(result.unreportedObjectKeys()).containsExactly(unreportedKey);
    }

    @Test
    void deleteAll_sameKeyInDeletedAndError_treatsErrorAsFailure() {
        S3PhotoStorageService service = new S3PhotoStorageService(s3Presigner, s3Client, BUCKET, TTL);
        String objectKey = "deadbeef/photos/a.jpg";
        when(s3Client.deleteObjects(any(DeleteObjectsRequest.class)))
                .thenReturn(DeleteObjectsResponse.builder()
                        .deleted(DeletedObject.builder().key(objectKey).build())
                        .errors(S3Error.builder().key(objectKey).code("InternalError").build())
                        .build());

        BatchDeleteResult result = service.deleteAll(List.of(objectKey));

        assertThat(result.deletedObjectKeys()).isEmpty();
        assertThat(result.errorCodeByObjectKey()).containsEntry(objectKey, "InternalError");
        assertThat(result.unreportedObjectKeys()).isEmpty();
    }

    @Test
    void deleteAll_sdkExceptionPropagatesWithoutBusinessExceptionTranslation() {
        S3PhotoStorageService service = new S3PhotoStorageService(s3Presigner, s3Client, BUCKET, TTL);
        SdkClientException sdkException = SdkClientException.create("connect timeout");
        when(s3Client.deleteObjects(any(DeleteObjectsRequest.class))).thenThrow(sdkException);

        assertThatThrownBy(() -> service.deleteAll(List.of("deadbeef/photos/a.jpg")))
                .isSameAs(sdkException);
    }
}
