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
 * 존재·크기 검증. target에 같은 크기 object가 이미 있으면 skip(멱등 재실행). 크기 불일치는 즉시
 * fail-closed 중단이다.
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
                    Long existingTargetSize = headContentLengthOrNull(targetKey);
                    if (existingTargetSize != null) {
                        if (existingTargetSize.equals(sourceObject.size())) {
                            objectsAlreadyPresent++; // 이미 복사됨 — 멱등 재실행
                            continue;
                        }
                        throw abortOnMismatch(objectsListed, objectsCopied, objectsAlreadyPresent,
                                "target 기존 object 크기 불일치");
                    }
                    s3Client.copyObject(CopyObjectRequest.builder()
                            .sourceBucket(bucket)
                            .sourceKey(sourceObject.key())
                            .destinationBucket(bucket)
                            .destinationKey(targetKey)
                            .build());
                    Long copiedSize = headContentLengthOrNull(targetKey);
                    if (!Objects.equals(copiedSize, sourceObject.size())) {
                        throw abortOnMismatch(objectsListed, objectsCopied, objectsAlreadyPresent,
                                copiedSize == null ? "copy 후 object 누락" : "copy 후 크기 불일치");
                    }
                    objectsCopied++;
                }
                continuationToken = page.nextContinuationToken();
            } while (continuationToken != null);
        }
        return new Result(userIds.size(), objectsListed, objectsCopied, objectsAlreadyPresent);
    }

    private Long headContentLengthOrNull(String objectKey) {
        try {
            return s3Client.headObject(HeadObjectRequest.builder()
                            .bucket(bucket)
                            .key(objectKey)
                            .build())
                    .contentLength();
        } catch (NoSuchKeyException e) {
            return null;
        }
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
