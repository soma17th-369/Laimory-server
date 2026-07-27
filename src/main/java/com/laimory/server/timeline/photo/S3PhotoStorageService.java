package com.laimory.server.timeline.photo;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import java.time.Duration;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/**
 * 사진 객체 저장 서비스(S3 기반). presigned PUT URL 발급 + 객체 삭제(단건·배치)만 담당한다.
 *
 * <p>구현이 하나뿐이라 인터페이스로 추상화하지 않는다(테스트는 {@link S3Presigner}/{@link S3Client}를 모킹).
 * 다중 구현이나 손으로 짠 fake가 필요해지면 그때 port 인터페이스를 도입한다.
 *
 * <p>업로드는 {@link S3Presigner}로 presigned PUT URL을 발급하고(클라이언트가 그 URL로 S3에 직접 PUT),
 * 삭제는 {@link S3Client#deleteObject}(단건)·{@link S3Client#deleteObjects}(배치)로 처리한다.
 * 버킷명({@code photo.s3.bucket})과 presign 유효시간({@code photo.upload.presign-ttl})은 설정에서
 * 주입한다. 자격증명/리전은 SDK 기본 체인({@code DefaultCredentialsProvider})으로 해석하므로 여기서
 * 명시하지 않는다.
 */
@Slf4j
@Service
public class S3PhotoStorageService {

    /** S3 DeleteObjects의 요청당 객체 수 상한(S3 API 한도) — 초과분은 순차 batch로 분할한다. */
    public static final int MAX_KEYS_PER_BATCH_DELETE = 1_000;

    // 배치 삭제는 동기 삭제 API 응답 시간에 직결되므로 요청 단위로만 타임아웃을 조인다
    // (전역 클라이언트 설정·단건 delete는 불변 — cleanup 스케줄러 등 기존 경로에 영향 없음).
    private static final Duration BATCH_DELETE_CALL_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration BATCH_DELETE_ATTEMPT_TIMEOUT = Duration.ofSeconds(3);

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

    /**
     * objectKey에 대한 presigned PUT URL을 발급한다.
     *
     * <p>서버는 데이터 경로에 없으므로 업로드 바이트를 가로채 검증할 수 없다. 대신 {@code contentType}과
     * {@code contentLength}를 서명에 바인딩해 <b>S3가 PUT 시점에 강제</b>하게 한다 — 서명된 값과 다른
     * Content-Type/Content-Length로 PUT하면 S3가 거부(403)한다. 따라서 URL을 받은(또는 탈취한) 클라이언트가
     * TTL 동안 임의 크기 객체를 올려 엔드포인트의 크기 검증을 우회하는 것을 막는다. 클라이언트는 파일 크기를
     * 알고 있으므로 정확한 {@code contentLength}를 선언할 수 있다.
     */
    public String generatePresignedPutUrl(String objectKey, String contentType, long contentLength) {
        PutObjectRequest por = PutObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .contentType(contentType)
                .contentLength(contentLength)
                .build();
        PutObjectPresignRequest req = PutObjectPresignRequest.builder()
                .signatureDuration(presignTtl)
                .putObjectRequest(por)
                .build();
        return s3Presigner.presignPutObject(req).url().toString();
    }

    public void delete(String objectKey) {
        DeleteObjectRequest request = DeleteObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .build();
        s3Client.deleteObject(request);
    }

    /**
     * 객체들을 {@code DeleteObjects}로 배치 삭제한다(최대 {@value #MAX_KEYS_PER_BATCH_DELETE}개/batch 순차 호출).
     * SDK 예외 또는 응답의 객체별 error가 1건이라도 있으면 {@link ExceptionType#PHOTO_BATCH_DELETE_FAILED}
     * (-1017, 502)를 던진다 — 호출부(삭제 파이프라인)는 이때 DB 삭제를 시작하지 않아 재시도로 수렴한다
     * (이미 지워진 key는 S3가 성공 처리하므로 재시도가 안전하다).
     *
     * <p>로그에 객체 key·URL은 남기지 않는다(개수·에러 코드만).
     */
    public void deleteAll(List<String> objectKeys) {
        for (int from = 0; from < objectKeys.size(); from += MAX_KEYS_PER_BATCH_DELETE) {
            List<String> chunk = objectKeys.subList(from, Math.min(from + MAX_KEYS_PER_BATCH_DELETE, objectKeys.size()));
            DeleteObjectsRequest request = DeleteObjectsRequest.builder()
                    .bucket(bucket)
                    .delete(Delete.builder()
                            .objects(chunk.stream()
                                    .map(key -> ObjectIdentifier.builder().key(key).build())
                                    .toList())
                            .build())
                    .overrideConfiguration(override -> override
                            .apiCallTimeout(BATCH_DELETE_CALL_TIMEOUT)
                            .apiCallAttemptTimeout(BATCH_DELETE_ATTEMPT_TIMEOUT))
                    .build();
            DeleteObjectsResponse response;
            try {
                response = s3Client.deleteObjects(request);
            } catch (SdkException e) {
                log.warn("photo batch delete failed (SDK exception): batchSize={} detail={}",
                        chunk.size(), e.getMessage());
                throw new BusinessException(ExceptionType.PHOTO_BATCH_DELETE_FAILED);
            }
            if (!response.errors().isEmpty()) {
                log.warn("photo batch delete partially failed: batchSize={} errorCount={} firstErrorCode={}",
                        chunk.size(), response.errors().size(), response.errors().get(0).code());
                throw new BusinessException(ExceptionType.PHOTO_BATCH_DELETE_FAILED);
            }
        }
    }
}
