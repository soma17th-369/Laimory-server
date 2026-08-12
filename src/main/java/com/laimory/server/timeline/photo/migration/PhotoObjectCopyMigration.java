package com.laimory.server.timeline.photo.migration;

import com.laimory.server.common.id.SubjectId;
import com.laimory.server.timeline.photo.PhotoObjectKeys;
import com.laimory.server.timeline.repository.TimelinePhotoDeleteJobRepository;
import com.laimory.server.user.SubjectMappingService;
import com.laimory.server.user.UserRepository;
import java.util.List;
import java.util.Objects;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * PHOTO S3 object의 legacy→subject namespace copy·검증 도구(#284, 계획 §5.3) —
 * {@code copy-verify} 모드. 역방향(rollback)은 지원하지 않으며, cutover 후 legacy object는 별도
 * 승인 하에 즉시 삭제한다(#285 runbook).
 *
 * <p>절차: preflight로 {@code timeline_photo_delete_jobs} 0건 확인(아니면 즉시 fail-closed — pending
 * delete object의 migration 정책을 임의로 만들지 않는다) → {@code users} 전 행 순회 → subject 해석 →
 * legacy namespace prefix의 object를 열거 → subject key로 {@code CopyObject} → {@code HeadObject}로
 * 존재·크기·Content-Type 검증(CloudFront가 object metadata의 Content-Type으로 직접 서빙하므로
 * Content-Type도 보존 대상이다). target에 크기·Content-Type이 모두 같은 object가 이미 있으면
 * skip(멱등 재실행). 크기는 같은데 Content-Type만 다른 target은 metadata drift로 보고 재복사로
 * 교정한다 — migration window 동안 legacy namespace가 단일 authority이고 {@code CopyObject} 기본
 * MetadataDirective=COPY가 source metadata를 그대로 복제하기 때문이다. 크기 불일치(기존 target·copy 후
 * 공통)와 copy 후 Content-Type 불일치는 즉시 fail-closed 중단이다.
 *
 * <p>로그·예외에 raw userId/HMAC/subject/object key/URL을 절대 남기지 않는다 — 건수만 보고한다.
 */
class PhotoObjectCopyMigration {

    private final S3Client s3Client;
    private final String bucket;
    private final UserRepository userRepository;
    private final SubjectMappingService subjectMappingService;
    private final TimelinePhotoDeleteJobRepository photoDeleteJobRepository;

    PhotoObjectCopyMigration(S3Client s3Client,
                             String bucket,
                             UserRepository userRepository,
                             SubjectMappingService subjectMappingService,
                             TimelinePhotoDeleteJobRepository photoDeleteJobRepository) {
        this.s3Client = s3Client;
        this.bucket = bucket;
        this.userRepository = userRepository;
        this.subjectMappingService = subjectMappingService;
        this.photoDeleteJobRepository = photoDeleteJobRepository;
    }

    Result execute() {
        long pendingDeleteJobs = photoDeleteJobRepository.count();
        if (pendingDeleteJobs != 0) {
            throw new PhotoMigrationAbortedException(
                    "pending photo delete job 존재로 중단: pendingDeleteJobs=" + pendingDeleteJobs);
        }

        List<Long> userIds = userRepository.findAllUserIds();
        long objectsListed = 0;
        long objectsCopied = 0;
        long objectsAlreadyPresent = 0;
        for (Long userId : userIds) {
            SubjectId subjectId = subjectMappingService.getRequired(userId);
            String sourcePrefix = PhotoObjectKeys.sha256hex(userId) + "/photos/";
            String targetPrefix = PhotoObjectKeys.subjectNamespace(subjectId) + "/photos/";

            String continuationToken = null;
            do {
                ListObjectsV2Response page = s3Client.listObjectsV2(ListObjectsV2Request.builder()
                        .bucket(bucket)
                        .prefix(sourcePrefix)
                        .continuationToken(continuationToken)
                        .build());
                for (S3Object sourceObject : page.contents()) {
                    objectsListed++;
                    String targetKey =
                            targetPrefix + sourceObject.key().substring(sourcePrefix.length());
                    ObjectSnapshot source = headSnapshotOrNull(sourceObject.key());
                    if (source == null) {
                        throw abortOnMismatch(objectsListed, objectsCopied, objectsAlreadyPresent,
                                "source object 누락");
                    }
                    ObjectSnapshot existingTarget = headSnapshotOrNull(targetKey);
                    if (existingTarget != null) {
                        if (existingTarget.equals(source)) {
                            objectsAlreadyPresent++; // 이미 복사됨 — 멱등 재실행
                            continue;
                        }
                        if (!Objects.equals(existingTarget.contentLength(),
                                source.contentLength())) {
                            throw abortOnMismatch(objectsListed, objectsCopied,
                                    objectsAlreadyPresent, "target 기존 object 크기 불일치");
                        }
                        // 크기는 같고 Content-Type만 다른 target — metadata drift로 보고 아래
                        // 재복사(기본 MetadataDirective=COPY가 source metadata 복제)로 교정한다.
                    }
                    s3Client.copyObject(CopyObjectRequest.builder()
                            .sourceBucket(bucket)
                            .sourceKey(sourceObject.key())
                            .destinationBucket(bucket)
                            .destinationKey(targetKey)
                            .build());
                    ObjectSnapshot copied = headSnapshotOrNull(targetKey);
                    if (copied == null) {
                        throw abortOnMismatch(objectsListed, objectsCopied, objectsAlreadyPresent,
                                "copy 후 object 누락");
                    }
                    if (!Objects.equals(copied.contentLength(), source.contentLength())) {
                        throw abortOnMismatch(objectsListed, objectsCopied, objectsAlreadyPresent,
                                "copy 후 크기 불일치");
                    }
                    if (!Objects.equals(copied.contentType(), source.contentType())) {
                        throw abortOnMismatch(objectsListed, objectsCopied, objectsAlreadyPresent,
                                "copy 후 contentType 불일치");
                    }
                    objectsCopied++;
                }
                continuationToken = page.nextContinuationToken();
            } while (continuationToken != null);
        }
        return new Result(userIds.size(), objectsListed, objectsCopied, objectsAlreadyPresent);
    }

    private ObjectSnapshot headSnapshotOrNull(String objectKey) {
        try {
            HeadObjectResponse head = s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(bucket)
                    .key(objectKey)
                    .build());
            return new ObjectSnapshot(head.contentLength(), head.contentType());
        } catch (NoSuchKeyException e) {
            return null;
        }
    }

    /** 검증용 최소 metadata snapshot — 값은 로그·예외에 절대 출력하지 않는다(불일치 여부만). */
    private record ObjectSnapshot(Long contentLength, String contentType) {
    }

    private static PhotoMigrationAbortedException abortOnMismatch(
            long objectsListed, long objectsCopied, long objectsAlreadyPresent, String reason) {
        // 첫 불일치에서 즉시 중단(fail-closed) — 이후 object로 진행하지 않는다. 건수만 보고.
        return new PhotoMigrationAbortedException("object copy 검증 실패(" + reason + "): mismatches=1"
                + " objectsListed=" + objectsListed
                + " objectsCopied=" + objectsCopied
                + " objectsAlreadyPresent=" + objectsAlreadyPresent);
    }

    /** 건수 전용 실행 결과 — 식별자 값 없음. */
    record Result(long usersProcessed, long objectsListed, long objectsCopied,
                  long objectsAlreadyPresent) {
    }
}
