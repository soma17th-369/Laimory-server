package com.laimory.server.timeline.photo;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/**
 * 사진 객체 저장 서비스(S3 API 기반 — AWS S3와 Cloudflare R2). presigned PUT URL 발급 + 객체 batch 삭제를 담당한다.
 *
 * <p>구현이 하나뿐이라 인터페이스로 추상화하지 않는다(테스트는 {@link S3Presigner}/{@link S3Client}를 모킹).
 * 다중 구현이나 손으로 짠 fake가 필요해지면 그때 port 인터페이스를 도입한다.
 *
 * <p><b>저장소 구성(S3 → R2 이전)</b>: 설정된 저장소는 AWS S3({@code photo.s3.bucket})와 R2
 * ({@link R2PhotoBucket}, {@code photo.r2.bucket}이 있을 때만)다. {@code photo.storage.primary}(기본
 * {@code s3})가 고른 저장소에만 presign하고, <b>삭제는 설정된 저장소 전부에</b> 한다 — 이전 기간에는 같은
 * 사진이 양쪽에 있을 수 있고, 계정 삭제의 파기 의무는 사본 위치와 무관하기 때문이다. S3는 primary이거나
 * bucket 값이 있을 때 포함한다(R2 primary + S3 bucket 비움 = R2 단독).
 *
 * <p>업로드는 primary의 {@link S3Presigner}로 presigned PUT URL을 발급하고(클라이언트가 그 URL로 직접 PUT),
 * 삭제는 {@link S3Client#deleteObjects}로 처리한다. presign 유효시간({@code photo.upload.presign-ttl})은
 * 설정에서 주입한다. AWS S3의 자격증명/리전은 SDK 기본 체인({@code DefaultCredentialsProvider})으로
 * 해석하므로 여기서 명시하지 않는다.
 */
@Service
public class S3PhotoStorageService {

    /** S3 DeleteObjects의 요청당 객체 수 상한(S3 API 한도) — 초과분은 순차 batch로 분할한다. */
    public static final int MAX_KEYS_PER_BATCH_DELETE = 1_000;

    // finalized/draft 배치 worker가 한 S3 호출에 무기한 점유되지 않도록 요청 단위로만 타임아웃을 조인다.
    // 전역 클라이언트 설정은 바꾸지 않는다.
    private static final Duration BATCH_DELETE_CALL_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration BATCH_DELETE_ATTEMPT_TIMEOUT = Duration.ofSeconds(3);

    private final Store primary;
    private final List<Store> stores;
    private final Duration presignTtl;

    /** AWS S3 단독 구성 — 이전 전 동작 그대로다. */
    public S3PhotoStorageService(S3Presigner s3Presigner, S3Client s3Client, String bucket, Duration presignTtl) {
        this(s3Presigner, s3Client, bucket, presignTtl, "s3", null);
    }

    /**
     * @param r2 R2 미설정이면 {@code null}
     * @throws IllegalArgumentException primary가 {@code s3|r2}가 아닐 때
     * @throws IllegalStateException    primary가 {@code r2}인데 R2 설정이 없을 때(기동 실패)
     */
    @Autowired
    public S3PhotoStorageService(
            S3Presigner s3Presigner,
            S3Client s3Client,
            @Value("${photo.s3.bucket}") String bucket,
            @Value("${photo.upload.presign-ttl}") Duration presignTtl,
            @Value("${photo.storage.primary:s3}") String primaryStorage,
            @Nullable R2PhotoBucket r2) {
        this.presignTtl = presignTtl;
        Store s3 = new Store(bucket, s3Client, s3Presigner, true);
        Store r2Store = r2 == null ? null : new Store(r2.name(), r2.client(), r2.presigner(), false);
        switch (primaryStorage) {
            case "s3" -> this.primary = s3;
            case "r2" -> {
                if (r2Store == null) {
                    throw new IllegalStateException("photo.storage.primary=r2에는 photo.r2.* 설정이 필요합니다");
                }
                this.primary = r2Store;
            }
            default -> throw new IllegalArgumentException(
                    "photo.storage.primary는 s3 또는 r2여야 합니다: " + primaryStorage);
        }
        List<Store> configured = new ArrayList<>();
        if (primary == s3 || (bucket != null && !bucket.isBlank())) {
            configured.add(s3);
        }
        if (r2Store != null) {
            configured.add(r2Store);
        }
        this.stores = List.copyOf(configured);
    }

    /**
     * primary 저장소의 objectKey에 대한 presigned PUT URL을 발급한다.
     *
     * <p>서버는 데이터 경로에 없으므로 업로드 바이트를 가로채 검증할 수 없다. 대신 {@code contentType}과
     * {@code contentLength}를 서명에 바인딩해 <b>저장소가 PUT 시점에 강제</b>하게 한다 — 서명된 값과 다른
     * Content-Type/Content-Length로 PUT하면 거부(403)된다. 따라서 URL을 받은(또는 탈취한) 클라이언트가
     * TTL 동안 임의 크기 객체를 올려 엔드포인트의 크기 검증을 우회하는 것을 막는다. 클라이언트는 파일 크기를
     * 알고 있으므로 정확한 {@code contentLength}를 선언할 수 있다.
     */
    public String generatePresignedPutUrl(String objectKey, String contentType, long contentLength) {
        PutObjectRequest por = PutObjectRequest.builder()
                .bucket(primary.bucket())
                .key(objectKey)
                .contentType(contentType)
                .contentLength(contentLength)
                .build();
        PutObjectPresignRequest req = PutObjectPresignRequest.builder()
                .signatureDuration(presignTtl)
                .putObjectRequest(por)
                .build();
        return primary.presigner().presignPutObject(req).url().toString();
    }

    /**
     * 객체들을 설정된 저장소마다 verbose {@code DeleteObjects}로 배치 삭제하고 요청 key별 결과를 반환한다.
     *
     * <p>요청은 저장소마다 최대 {@value #MAX_KEYS_PER_BATCH_DELETE}개씩 순차 호출한다. key는 <b>모든 저장소가
     * {@code Deleted}로 확인했을 때만</b> 삭제로 본다 — 한 저장소라도 {@code Error}면 error, 그 외는 미보고다.
     * 호출자가 성공한 작업만 완료하게 하는 계약은 저장소가 하나일 때와 같다. 미존재 객체 삭제는
     * {@code Deleted}로 응답되므로 반복 호출도 성공으로 수렴한다.
     *
     * <p>SDK/transport 예외는 그대로 전파한다. worker는 이 경우 요청 작업을 모두 남겨 다음 실행에서
     * 재시도해야 한다(먼저 지워진 저장소의 재삭제는 {@code Deleted}로 수렴한다).
     */
    public BatchDeleteResult deleteAll(List<String> objectKeys) throws SdkException {
        List<String> distinctObjectKeys = objectKeys.stream().distinct().toList();
        List<BatchDeleteResult> perStore = new ArrayList<>();
        for (Store store : stores) {
            perStore.add(deleteAllIn(store, distinctObjectKeys));
        }

        Map<String, String> errorCodeByObjectKey = new LinkedHashMap<>();
        perStore.forEach(result -> result.errorCodeByObjectKey().forEach(errorCodeByObjectKey::putIfAbsent));
        Set<String> deletedObjectKeys = new LinkedHashSet<>();
        Set<String> unreportedObjectKeys = new LinkedHashSet<>();
        for (String key : distinctObjectKeys) {
            if (errorCodeByObjectKey.containsKey(key)) {
                continue;
            }
            if (perStore.stream().allMatch(result -> result.deletedObjectKeys().contains(key))) {
                deletedObjectKeys.add(key);
            } else {
                unreportedObjectKeys.add(key);
            }
        }
        return new BatchDeleteResult(deletedObjectKeys, errorCodeByObjectKey, unreportedObjectKeys);
    }

    private BatchDeleteResult deleteAllIn(Store store, List<String> distinctObjectKeys) {
        Set<String> deletedObjectKeys = new LinkedHashSet<>();
        Map<String, String> errorCodeByObjectKey = new LinkedHashMap<>();
        Set<String> unreportedObjectKeys = new LinkedHashSet<>();

        for (int from = 0; from < distinctObjectKeys.size(); from += MAX_KEYS_PER_BATCH_DELETE) {
            List<String> chunk = distinctObjectKeys.subList(
                    from, Math.min(from + MAX_KEYS_PER_BATCH_DELETE, distinctObjectKeys.size()));
            DeleteObjectsResponse response = store.client().deleteObjects(deleteRequest(store, chunk.stream()
                    .map(key -> ObjectIdentifier.builder().key(key).build())
                    .toList()));

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

    /**
     * prefix 아래 남은 객체를 한 페이지 읽는다(#302 계정 삭제). 저장소를 순서대로 보고 <b>처음으로 비지 않은
     * 저장소의 페이지</b>를 돌려준다 — 빈 목록은 설정된 모든 저장소의 prefix가 비었다는 뜻이다.
     *
     * <p><b>AWS S3는 {@code ListObjectsV2}가 아니라 version과 delete marker까지 읽는다</b>: 그쪽은 current
     * version만 보여 준다. bucket에 versioning이 켜지면 versionId 없는 삭제가 delete marker만 만들고 원본
     * bytes는 noncurrent version으로 남는데, {@code ListObjectsV2}는 그 상태를 <b>빈 목록</b>으로 보고한다 —
     * 계정 삭제가 "다 지웠다"고 오판하고 mapping·job까지 지워 사진을 영구히 되찾을 수 없게 만든다. 이 API는
     * 남은 version과 marker를 그대로 돌려주므로 bucket 설정과 무관하게 정직하다.
     *
     * <p><b>R2는 버전 API({@code ListObjectVersions})가 없고 버전을 두지 않는다</b> — current 목록
     * ({@code ListObjectsV2})이 전부라 같은 오판이 생기지 않는다. R2 항목의 versionId는 {@code null}이다.
     *
     * <p>전체를 memory에 모으지 않으려고 페이지 단위로 돌려준다. 호출자는 목록이 빌 때까지
     * "한 페이지 조회 → 그 페이지 삭제"를 반복한다 — 지우면서 읽으므로 continuation token이 필요 없다.
     *
     * @return 이 페이지의 (key, versionId) — 빈 목록 = 모든 저장소의 prefix 아래에 아무것도 남지 않았다
     */
    public List<ObjectVersion> listObjectVersions(String prefix, int maxKeys) throws SdkException {
        for (Store store : stores) {
            List<ObjectVersion> page = store.versioned()
                    ? listVersionsIn(store, prefix, maxKeys)
                    : listCurrentIn(store, prefix, maxKeys);
            if (!page.isEmpty()) {
                return page;
            }
        }
        return List.of();
    }

    private List<ObjectVersion> listVersionsIn(Store store, String prefix, int maxKeys) {
        ListObjectVersionsResponse response = store.client().listObjectVersions(ListObjectVersionsRequest.builder()
                .bucket(store.bucket())
                .prefix(prefix)
                .maxKeys(maxKeys)
                .overrideConfiguration(override -> override
                        .apiCallTimeout(BATCH_DELETE_CALL_TIMEOUT)
                        .apiCallAttemptTimeout(BATCH_DELETE_ATTEMPT_TIMEOUT))
                .build());
        List<ObjectVersion> versions = new ArrayList<>();
        response.versions().forEach(v -> versions.add(new ObjectVersion(v.key(), v.versionId())));
        response.deleteMarkers().forEach(m -> versions.add(new ObjectVersion(m.key(), m.versionId())));
        return List.copyOf(versions);
    }

    private List<ObjectVersion> listCurrentIn(Store store, String prefix, int maxKeys) {
        return store.client().listObjectsV2(ListObjectsV2Request.builder()
                        .bucket(store.bucket())
                        .prefix(prefix)
                        .maxKeys(maxKeys)
                        .overrideConfiguration(override -> override
                                .apiCallTimeout(BATCH_DELETE_CALL_TIMEOUT)
                                .apiCallAttemptTimeout(BATCH_DELETE_ATTEMPT_TIMEOUT))
                        .build())
                .contents().stream()
                .map(object -> new ObjectVersion(object.key(), null))
                .toList();
    }

    /**
     * (key, versionId)를 설정된 저장소마다 배치 삭제한다. 항목은 <b>모든 저장소가 삭제를 확인했을 때만</b>
     * 결과에 들어간다.
     *
     * <p>AWS S3에는 version을 지정해 보낸다 — versioning bucket에서도 delete marker를 남기지 않고 실제로
     * 제거한다. unversioned bucket에서는 versionId가 {@code "null"}이고 그 값이 곧 그 객체라 동작이 같다.
     * versionId가 {@code null}인 항목(R2 목록에서 온 것)은 version 없이 지운다. R2에는 key만 보낸다(버전 없음).
     *
     * <p>{@link #deleteAll(List)}과 달리 결과의 key는 {@code key@versionId}이고 같은 key가 여러 번 나올 수
     * 있다(같은 key의 여러 version). 호출자는 요청 수와 확인된 삭제 수를 비교해 완료를 판정한다.
     */
    public BatchDeleteResult deleteVersions(List<ObjectVersion> objectVersions) throws SdkException {
        Map<String, String> errorCodeByObjectKey = new LinkedHashMap<>();
        Set<String> deleted = null;
        for (Store store : stores) {
            Set<String> confirmed = store.versioned()
                    ? deleteVersionsIn(store, objectVersions, errorCodeByObjectKey)
                    : deleteKeysOfVersionsIn(store, objectVersions, errorCodeByObjectKey);
            if (deleted == null) {
                deleted = confirmed;
            } else {
                deleted.retainAll(confirmed);
            }
        }
        return new BatchDeleteResult(deleted == null ? Set.of() : deleted, errorCodeByObjectKey, Set.of());
    }

    private Set<String> deleteVersionsIn(
            Store store, List<ObjectVersion> objectVersions, Map<String, String> errorCodeByObjectKey) {
        Set<String> deleted = new LinkedHashSet<>();
        for (int from = 0; from < objectVersions.size(); from += MAX_KEYS_PER_BATCH_DELETE) {
            List<ObjectVersion> chunk = objectVersions.subList(
                    from, Math.min(from + MAX_KEYS_PER_BATCH_DELETE, objectVersions.size()));
            DeleteObjectsResponse response = store.client().deleteObjects(deleteRequest(store, chunk.stream()
                    .map(v -> ObjectIdentifier.builder().key(v.key()).versionId(v.versionId()).build())
                    .toList()));
            response.errors().forEach(error ->
                    errorCodeByObjectKey.putIfAbsent(error.key(), normalizedErrorCode(error.code())));
            response.deleted().forEach(d -> deleted.add(versionKey(d.key(), d.versionId())));
        }
        return deleted;
    }

    /** 버전 없는 저장소 — 항목들의 key를 한 번씩 지우고, 확인된 key의 모든 항목을 삭제로 본다. */
    private Set<String> deleteKeysOfVersionsIn(
            Store store, List<ObjectVersion> objectVersions, Map<String, String> errorCodeByObjectKey) {
        List<String> keys = objectVersions.stream().map(ObjectVersion::key).distinct().toList();
        Set<String> deletedKeys = new HashSet<>();
        for (int from = 0; from < keys.size(); from += MAX_KEYS_PER_BATCH_DELETE) {
            List<String> chunk = keys.subList(from, Math.min(from + MAX_KEYS_PER_BATCH_DELETE, keys.size()));
            DeleteObjectsResponse response = store.client().deleteObjects(deleteRequest(store, chunk.stream()
                    .map(key -> ObjectIdentifier.builder().key(key).build())
                    .toList()));
            response.errors().forEach(error ->
                    errorCodeByObjectKey.putIfAbsent(error.key(), normalizedErrorCode(error.code())));
            response.deleted().forEach(d -> deletedKeys.add(d.key()));
        }
        Set<String> deleted = new LinkedHashSet<>();
        objectVersions.stream()
                .filter(v -> deletedKeys.contains(v.key()) && !errorCodeByObjectKey.containsKey(v.key()))
                .forEach(v -> deleted.add(versionKey(v.key(), v.versionId())));
        return deleted;
    }

    private static DeleteObjectsRequest deleteRequest(Store store, List<ObjectIdentifier> objects) {
        return DeleteObjectsRequest.builder()
                .bucket(store.bucket())
                .delete(Delete.builder()
                        .objects(objects)
                        .quiet(false)
                        .build())
                .overrideConfiguration(override -> override
                        .apiCallTimeout(BATCH_DELETE_CALL_TIMEOUT)
                        .apiCallAttemptTimeout(BATCH_DELETE_ATTEMPT_TIMEOUT))
                .build();
    }

    private static String versionKey(String key, String versionId) {
        return key + "@" + versionId;
    }

    /** prefix 아래 남은 객체 하나 — version이거나 delete marker다. 버전 없는 저장소(R2)의 항목은 versionId가 {@code null}이다. */
    public record ObjectVersion(String key, String versionId) {
    }

    /** 설정된 저장소 하나. {@code versioned}는 version API로 목록·삭제하는지(AWS S3) 여부다. */
    private record Store(String bucket, S3Client client, S3Presigner presigner, boolean versioned) {
    }

    private static String normalizedErrorCode(String errorCode) {
        return errorCode == null || errorCode.isBlank() ? "Unknown" : errorCode;
    }

    /**
     * verbose {@code DeleteObjects}의 요청 key별 결과.
     *
     * @param deletedObjectKeys 모든 저장소가 {@code Deleted}로 확인한 key(미존재 객체 포함)
     * @param errorCodeByObjectKey 어느 저장소든 {@code Error}로 반환한 key와 error code
     * @param unreportedObjectKeys 삭제도 error도 확정되지 않은 요청 key
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
