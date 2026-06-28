package com.laimory.server.timeline.photo;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/**
 * AWS S3 기반 {@link PhotoStorageService} 구현.
 *
 * <p>업로드는 {@link S3Presigner}로 presigned PUT URL을 발급하고(클라이언트가 그 URL로 S3에 직접 PUT),
 * 삭제는 {@link S3Client#deleteObject}로 처리한다. 버킷명({@code photo.s3.bucket})과 presign 유효시간
 * ({@code photo.upload.presign-ttl})은 설정에서 주입한다. 자격증명/리전은 SDK 기본 체인
 * ({@code DefaultCredentialsProvider})으로 해석하므로 여기서 명시하지 않는다.
 */
@Service
public class S3PhotoStorageService implements PhotoStorageService {

    private final S3Presigner s3Presigner;
    private final S3Client s3Client;
    private final String bucket;
    private final Duration presignTtl;

    public S3PhotoStorageService(
            S3Presigner s3Presigner,
            S3Client s3Client,
            @Value("${photo.s3.bucket}") String bucket,
            @Value("${photo.upload.presign-ttl}") Duration presignTtl) {
        this.s3Presigner = s3Presigner;
        this.s3Client = s3Client;
        this.bucket = bucket;
        this.presignTtl = presignTtl;
    }

    @Override
    public String generatePresignedPutUrl(String objectKey, String contentType) {
        PutObjectRequest por = PutObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .contentType(contentType)
                .build();
        PutObjectPresignRequest req = PutObjectPresignRequest.builder()
                .signatureDuration(presignTtl)
                .putObjectRequest(por)
                .build();
        return s3Presigner.presignPutObject(req).url().toString();
    }

    @Override
    public void delete(String objectKey) {
        DeleteObjectRequest request = DeleteObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .build();
        s3Client.deleteObject(request);
    }
}
