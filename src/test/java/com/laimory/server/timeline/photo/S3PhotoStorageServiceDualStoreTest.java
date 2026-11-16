package com.laimory.server.timeline.photo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.laimory.server.timeline.photo.S3PhotoStorageService.BatchDeleteResult;
import com.laimory.server.timeline.photo.S3PhotoStorageService.ObjectVersion;
import java.net.URI;
import java.time.Duration;
import java.util.List;
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
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.S3Error;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/**
 * S3 → R2 이전 기간의 저장소 구성 검증 — presign은 primary 하나, 삭제는 설정된 저장소 전부.
 * 두 저장소의 클라이언트를 각각 모킹한다(인프라 0).
 */
@ExtendWith(MockitoExtension.class)
class S3PhotoStorageServiceDualStoreTest {

    private static final String S3_BUCKET = "s3-bucket";
    private static final String R2_BUCKET = "r2-bucket";
    private static final Duration TTL = Duration.ofMinutes(10);
    private static final String KEY = "deadbeef/photos/a.jpg";

    @Mock
    private S3Presigner s3Presigner;

    @Mock
    private S3Client s3Client;

    @Mock
    private S3Presigner r2Presigner;

    @Mock
    private S3Client r2Client;

    @Mock
    private PresignedPutObjectRequest presignedRequest;

    private S3PhotoStorageService service(String primary, String s3Bucket) {
        return new S3PhotoStorageService(s3Presigner, s3Client, s3Bucket, TTL, primary,
                new R2PhotoBucket(R2_BUCKET, r2Client, r2Presigner));
    }

    private static DeleteObjectsResponse deleted(String... keys) {
        return DeleteObjectsResponse.builder()
                .deleted(java.util.Arrays.stream(keys).map(k -> DeletedObject.builder().key(k).build()).toList())
                .build();
    }

    @Test
    void presignUsesR2BucketAndPresignerWhenPrimaryIsR2() throws Exception {
        S3PhotoStorageService service = service("r2", S3_BUCKET);
        URI r2Url = URI.create("https://account.r2.cloudflarestorage.com/r2-bucket/" + KEY + "?X-Amz-Signature=x");
        when(presignedRequest.url()).thenReturn(r2Url.toURL());
        when(r2Presigner.presignPutObject(any(PutObjectPresignRequest.class))).thenReturn(presignedRequest);

        String url = service.generatePresignedPutUrl(KEY, "image/jpeg", 100L);

        assertThat(url).isEqualTo(r2Url.toString());
        ArgumentCaptor<PutObjectPresignRequest> captor = ArgumentCaptor.forClass(PutObjectPresignRequest.class);
        verify(r2Presigner).presignPutObject(captor.capture());
        assertThat(captor.getValue().putObjectRequest().bucket()).isEqualTo(R2_BUCKET);
        assertThat(captor.getValue().putObjectRequest().contentLength()).isEqualTo(100L);
        verify(s3Presigner, never()).presignPutObject(any(PutObjectPresignRequest.class));
    }

    @Test
    void presignStaysOnS3WhenR2IsOnlyConfiguredAsSecondary() throws Exception {
        S3PhotoStorageService service = service("s3", S3_BUCKET);
        when(presignedRequest.url()).thenReturn(URI.create("https://s3.example/" + KEY).toURL());
        when(s3Presigner.presignPutObject(any(PutObjectPresignRequest.class))).thenReturn(presignedRequest);

        service.generatePresignedPutUrl(KEY, "image/jpeg", 100L);

        verify(r2Presigner, never()).presignPutObject(any(PutObjectPresignRequest.class));
    }

    @Test
    void primaryR2WithoutR2ConfigurationFailsAtConstruction() {
        assertThatThrownBy(() -> new S3PhotoStorageService(s3Presigner, s3Client, S3_BUCKET, TTL, "r2", null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void unknownPrimaryFailsAtConstruction() {
        assertThatThrownBy(() -> service("gcs", S3_BUCKET)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deleteAllCountsKeyAsDeletedOnlyWhenBothStoresDeletedIt() {
        S3PhotoStorageService service = service("r2", S3_BUCKET);
        when(s3Client.deleteObjects(any(DeleteObjectsRequest.class))).thenReturn(deleted(KEY));
        when(r2Client.deleteObjects(any(DeleteObjectsRequest.class))).thenReturn(deleted(KEY));

        BatchDeleteResult result = service.deleteAll(List.of(KEY));

        assertThat(result.deletedObjectKeys()).containsExactly(KEY);
        ArgumentCaptor<DeleteObjectsRequest> s3Request = ArgumentCaptor.forClass(DeleteObjectsRequest.class);
        ArgumentCaptor<DeleteObjectsRequest> r2Request = ArgumentCaptor.forClass(DeleteObjectsRequest.class);
        verify(s3Client).deleteObjects(s3Request.capture());
        verify(r2Client).deleteObjects(r2Request.capture());
        assertThat(s3Request.getValue().bucket()).isEqualTo(S3_BUCKET);
        assertThat(r2Request.getValue().bucket()).isEqualTo(R2_BUCKET);
    }

    @Test
    void deleteAllTreatsErrorInOneStoreAsFailure() {
        S3PhotoStorageService service = service("r2", S3_BUCKET);
        when(s3Client.deleteObjects(any(DeleteObjectsRequest.class))).thenReturn(deleted(KEY));
        when(r2Client.deleteObjects(any(DeleteObjectsRequest.class))).thenReturn(DeleteObjectsResponse.builder()
                .errors(S3Error.builder().key(KEY).code("InternalError").build())
                .build());

        BatchDeleteResult result = service.deleteAll(List.of(KEY));

        assertThat(result.deletedObjectKeys()).isEmpty();
        assertThat(result.errorCodeByObjectKey()).containsEntry(KEY, "InternalError");
    }

    @Test
    void deleteAllReportsKeyMissingFromOneStoreResponseAsUnreported() {
        S3PhotoStorageService service = service("r2", S3_BUCKET);
        when(s3Client.deleteObjects(any(DeleteObjectsRequest.class))).thenReturn(deleted(KEY));
        when(r2Client.deleteObjects(any(DeleteObjectsRequest.class))).thenReturn(DeleteObjectsResponse.builder().build());

        BatchDeleteResult result = service.deleteAll(List.of(KEY));

        assertThat(result.deletedObjectKeys()).isEmpty();
        assertThat(result.unreportedObjectKeys()).containsExactly(KEY);
    }

    @Test
    void deleteAllPropagatesSecondStoreExceptionAfterFirstStoreDeleted() {
        S3PhotoStorageService service = service("r2", S3_BUCKET);
        SdkClientException failure = SdkClientException.create("connect timeout");
        when(s3Client.deleteObjects(any(DeleteObjectsRequest.class))).thenReturn(deleted(KEY));
        when(r2Client.deleteObjects(any(DeleteObjectsRequest.class))).thenThrow(failure);

        assertThatThrownBy(() -> service.deleteAll(List.of(KEY))).isSameAs(failure);
    }

    @Test
    void r2PrimaryWithBlankS3BucketDeletesOnlyFromR2() {
        S3PhotoStorageService service = service("r2", "");
        when(r2Client.deleteObjects(any(DeleteObjectsRequest.class))).thenReturn(deleted(KEY));

        BatchDeleteResult result = service.deleteAll(List.of(KEY));

        assertThat(result.deletedObjectKeys()).containsExactly(KEY);
        verify(s3Client, never()).deleteObjects(any(DeleteObjectsRequest.class));
    }

    @Test
    void listObjectVersionsReadsR2CurrentObjectsOnceS3PrefixIsEmpty() {
        S3PhotoStorageService service = service("r2", S3_BUCKET);
        when(s3Client.listObjectVersions(any(ListObjectVersionsRequest.class)))
                .thenReturn(ListObjectVersionsResponse.builder().build());
        when(r2Client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(ListObjectsV2Response.builder()
                .contents(S3Object.builder().key("ns/photos/a.jpg").build(),
                        S3Object.builder().key("ns/photos/b.jpg").build())
                .build());

        List<ObjectVersion> page = service.listObjectVersions("ns/photos/", 500);

        // R2에는 버전이 없어 current 목록이 전부다 — versionId 없이 key만 돌려준다.
        assertThat(page).containsExactly(
                new ObjectVersion("ns/photos/a.jpg", null), new ObjectVersion("ns/photos/b.jpg", null));
        ArgumentCaptor<ListObjectsV2Request> captor = ArgumentCaptor.forClass(ListObjectsV2Request.class);
        verify(r2Client).listObjectsV2(captor.capture());
        assertThat(captor.getValue().bucket()).isEqualTo(R2_BUCKET);
        assertThat(captor.getValue().prefix()).isEqualTo("ns/photos/");
        assertThat(captor.getValue().maxKeys()).isEqualTo(500);
    }

    @Test
    void listObjectVersionsReturnsS3PageWithoutListingR2WhileS3HasObjects() {
        S3PhotoStorageService service = service("r2", S3_BUCKET);
        when(s3Client.listObjectVersions(any(ListObjectVersionsRequest.class))).thenReturn(
                ListObjectVersionsResponse.builder()
                        .versions(software.amazon.awssdk.services.s3.model.ObjectVersion.builder()
                                .key("ns/photos/a.jpg").versionId("null").build())
                        .build());

        List<ObjectVersion> page = service.listObjectVersions("ns/photos/", 500);

        assertThat(page).containsExactly(new ObjectVersion("ns/photos/a.jpg", "null"));
        verify(r2Client, never()).listObjectsV2(any(ListObjectsV2Request.class));
    }

    @Test
    void deleteVersionsSendsVersionsToS3AndDistinctKeysToR2AndCountsOnlyBothConfirmed() {
        S3PhotoStorageService service = service("r2", S3_BUCKET);
        when(s3Client.deleteObjects(any(DeleteObjectsRequest.class))).thenReturn(DeleteObjectsResponse.builder()
                .deleted(DeletedObject.builder().key("k1").versionId("v1").build(),
                        DeletedObject.builder().key("k1").versionId("v2").build(),
                        DeletedObject.builder().key("k2").versionId("v1").build())
                .build());
        when(r2Client.deleteObjects(any(DeleteObjectsRequest.class))).thenReturn(DeleteObjectsResponse.builder()
                .deleted(DeletedObject.builder().key("k1").build())
                .errors(S3Error.builder().key("k2").code("InternalError").build())
                .build());

        BatchDeleteResult result = service.deleteVersions(List.of(
                new ObjectVersion("k1", "v1"), new ObjectVersion("k1", "v2"), new ObjectVersion("k2", "v1")));

        ArgumentCaptor<DeleteObjectsRequest> r2Request = ArgumentCaptor.forClass(DeleteObjectsRequest.class);
        verify(r2Client).deleteObjects(r2Request.capture());
        assertThat(r2Request.getValue().delete().objects())
                .extracting(ObjectIdentifier::key, ObjectIdentifier::versionId)
                .containsExactly(tuple("k1", null), tuple("k2", null));
        // k2는 R2에서 실패했으므로 S3 삭제가 확인돼도 완료가 아니다 — 호출자가 job을 남겨 재시도한다.
        assertThat(result.deletedObjectKeys()).containsExactlyInAnyOrder("k1@v1", "k1@v2");
        assertThat(result.errorCodeByObjectKey()).containsEntry("k2", "InternalError");
    }
}
