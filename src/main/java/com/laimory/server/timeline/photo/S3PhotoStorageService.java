package com.laimory.server.timeline.photo;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/**
 * 사진 객체 저장 서비스(S3 기반). presigned PUT URL 발급 + 객체 batch 삭제를 담당한다.
 *
 * <p>구현이 하나뿐이라 인터페이스로 추상화하지 않는다(테스트는 {@link S3Presigner}/{@link S3Client}를 모킹).
 * 다중 구현이나 손으로 짠 fake가 필요해지면 그때 port 인터페이스를 도입한다.
 *
 * <p>업로드는 {@link S3Presigner}로 presigned PUT URL을 발급하고(클라이언트가 그 URL로 S3에 직접 PUT),
 * 삭제는 {@link S3Client#deleteObjects}로 처리한다.
 * 버킷명({@code photo.s3.bucket})과 presign 유효시간({@code photo.upload.presign-ttl})은 설정에서
 * 주입한다. 자격증명/리전은 SDK 기본 체인({@code DefaultCredentialsProvider})으로 해석하므로 여기서
 * 명시하지 않는다.
 */
@Service
public class S3PhotoStorageService {

    /** S3 DeleteObjects의 요청당 객체 수 상한(S3 API 한도) — 초과분은 순차 batch로 분할한다. */
    public static final int MAX_KEYS_PER_BATCH_DELETE = 1_000;

    // finalized/draft 배치 worker가 한 S3 호출에 무기한 점유되지 않도록 요청 단위로만 타임아웃을 조인다.
    // 전역 클라이언트 설정은 바꾸지 않는다.
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

    /**
     * 객체들을 verbose {@code DeleteObjects}로 배치 삭제하고 요청 key별 결과를 반환한다.
     *
     * <p>요청은 최대 {@value #MAX_KEYS_PER_BATCH_DELETE}개씩 순차 호출한다. 응답의 {@code Deleted} key,
     * {@code Error} key, 어느 목록에도 없는 key를 구분해 호출자가 성공한 작업만 완료할 수 있게 한다.
     * S3가 미존재 객체 삭제를 {@code Deleted}로 응답하므로 반복 호출도 성공으로 수렴한다.
     *
     * <p>SDK/transport 예외는 그대로 전파한다. worker는 이 경우 요청 작업을 모두 남겨 다음 실행에서
     * 재시도해야 한다.
     */
    public BatchDeleteResult deleteAll(List<String> objectKeys) throws SdkException {
        Set<String> deletedObjectKeys = new LinkedHashSet<>();
        Map<String, String> errorCodeByObjectKey = new LinkedHashMap<>();
        Set<String> unreportedObjectKeys = new LinkedHashSet<>();

        List<String> distinctObjectKeys = objectKeys.stream().distinct().toList();
        for (int from = 0; from < distinctObjectKeys.size(); from += MAX_KEYS_PER_BATCH_DELETE) {
            List<String> chunk = distinctObjectKeys.subList(
                    from, Math.min(from + MAX_KEYS_PER_BATCH_DELETE, distinctObjectKeys.size()));
            DeleteObjectsRequest request = DeleteObjectsRequest.builder()
                    .bucket(bucket)
                    .delete(Delete.builder()
                            .objects(chunk.stream()
                                    .map(key -> ObjectIdentifier.builder().key(key).build())
                                    .toList())
                            .quiet(false)
                            .build())
                    .overrideConfiguration(override -> override
                            .apiCallTimeout(BATCH_DELETE_CALL_TIMEOUT)
                            .apiCallAttemptTimeout(BATCH_DELETE_ATTEMPT_TIMEOUT))
                    .build();
            DeleteObjectsResponse response = s3Client.deleteObjects(request);

            Set<String> requestedChunkKeys = new LinkedHashSet<>(chunk);
            response.errors().stream()
                    .filter(error -> requestedChunkKeys.contains(error.key()))
                    .forEach(error -> errorCodeByObjectKey.put(
                            error.key(), normalizedErrorCode(error.code())));
            response.deleted().stream()
                    .map(deleted -> deleted.key())
                    .filter(requestedChunkKeys::contains)
                    .filter(key -> !errorCodeByObjectKey.containsKey(key))
                    .forEach(deletedObjectKeys::add);

            Set<String> unreportedChunkKeys = new LinkedHashSet<>(requestedChunkKeys);
            unreportedChunkKeys.removeAll(deletedObjectKeys);
            unreportedChunkKeys.removeAll(errorCodeByObjectKey.keySet());
            unreportedObjectKeys.addAll(unreportedChunkKeys);
        }

        return new BatchDeleteResult(deletedObjectKeys, errorCodeByObjectKey, unreportedObjectKeys);
    }

    private static String normalizedErrorCode(String errorCode) {
        return errorCode == null || errorCode.isBlank() ? "Unknown" : errorCode;
    }

    /**
     * verbose {@code DeleteObjects}의 요청 key별 결과.
     *
     * @param deletedObjectKeys S3가 {@code Deleted}로 확인한 key(미존재 객체 포함)
     * @param errorCodeByObjectKey S3가 {@code Error}로 반환한 key와 error code
     * @param unreportedObjectKeys 응답의 {@code Deleted}/{@code Error} 어느 목록에도 없던 요청 key
     */
    public record BatchDeleteResult(
            Set<String> deletedObjectKeys,
            Map<String, String> errorCodeByObjectKey,
            Set<String> unreportedObjectKeys) {

        public BatchDeleteResult {
            deletedObjectKeys = Collections.unmodifiableSet(new LinkedHashSet<>(deletedObjectKeys));
            errorCodeByObjectKey =
                    Collections.unmodifiableMap(new LinkedHashMap<>(errorCodeByObjectKey));
            unreportedObjectKeys =
                    Collections.unmodifiableSet(new LinkedHashSet<>(unreportedObjectKeys));
        }
    }
}
