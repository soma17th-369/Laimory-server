package com.laimory.server.timeline.photo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/**
 * S3PhotoStorageService 단위 검증. S3Presigner/S3Client를 모킹해 presign/deleteObject 인자를 캡처한다.
 * 인프라 0.
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

    @Test
    void delete_deletesObjectWithExpectedBucketAndKey() {
        S3PhotoStorageService service = new S3PhotoStorageService(s3Presigner, s3Client, BUCKET, TTL);
        String objectKey = "deadbeef/photos/b.jpg";

        service.delete(objectKey);

        ArgumentCaptor<DeleteObjectRequest> requestCaptor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client).deleteObject(requestCaptor.capture());
        DeleteObjectRequest request = requestCaptor.getValue();
        assertThat(request.bucket()).isEqualTo(BUCKET);
        assertThat(request.key()).isEqualTo(objectKey);
    }
}
