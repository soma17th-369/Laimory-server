package com.laimory.server.timeline.photo.migration;

import com.laimory.server.timeline.photo.PhotoObjectKeys;
import com.laimory.server.user.SubjectMappingService;
import com.laimory.server.user.UserRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeletedObject;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * cutover 검증 완료 뒤 known user의 legacy PHOTO namespace를 제거하는 forward-only one-shot 도구.
 * 삭제 전에 users 전 행을 권위 목록으로 순회하면서 각 legacy object와 subject target의 크기·
 * Content-Type 동등성을 전부 재검증한다. 전체 검증이 끝난 뒤에만 source를 최대 1,000개씩 삭제하고,
 * users 전 행의 legacy prefix를 다시 열거해 잔여 0건을 fail-closed로 확인한다.
 *
 * <p>로그·예외에는 userId, subject, object key와 metadata 값을 남기지 않고 건수만 기록한다.
 */
class LegacyPhotoObjectDeleteMigration {

    private static final int DELETE_BATCH_SIZE = 1_000;

    private final S3Client s3Client;
    private final String bucket;
    private final UserRepository userRepository;
    private final SubjectMappingService subjectMappingService;

    LegacyPhotoObjectDeleteMigration(S3Client s3Client,
                                     String bucket,
                                     UserRepository userRepository,
                                     SubjectMappingService subjectMappingService) {
        this.s3Client = s3Client;
        this.bucket = bucket;
        this.userRepository = userRepository;
        this.subjectMappingService = subjectMappingService;
    }

    Result execute() {
        List<Long> userIds = userRepository.findAllUserIds();
        long objectsVerified = verifyAllTargets(userIds);
        long objectsDeleted = deleteAllLegacyObjects(userIds);
        long objectsRemaining = countLegacyObjects(userIds);
        if (objectsDeleted != objectsVerified) {
            throw aborted("삭제 건수 불일치", objectsVerified, objectsDeleted,
                    objectsRemaining);
        }
        if (objectsRemaining != 0) {
            throw aborted("삭제 후 legacy object 잔여", objectsVerified, objectsDeleted,
                    objectsRemaining);
        }
        return new Result(userIds.size(), objectsVerified, objectsDeleted, objectsRemaining);
    }

    /** 삭제 전에 전체 source 집합을 검증해 중간 사용자에서 불일치가 나도 부분 삭제가 없게 한다. */
    private long verifyAllTargets(List<Long> userIds) {
        long objectsVerified = 0;
        for (Long userId : userIds) {
            UUID subjectId = subjectMappingService.getRequired(userId);
            String sourcePrefix = legacyPrefix(userId);
            String targetPrefix = subjectPrefix(subjectId);
            String continuationToken = null;
            do {
                ListObjectsV2Response page = list(sourcePrefix, continuationToken);
                for (S3Object sourceObject : page.contents()) {
                    ObjectSnapshot source = headSnapshotOrNull(sourceObject.key());
                    String targetKey = targetPrefix
                            + sourceObject.key().substring(sourcePrefix.length());
                    ObjectSnapshot target = headSnapshotOrNull(targetKey);
                    if (source == null || !source.equals(target)) {
                        throw aborted("target 동등성 불일치", objectsVerified, 0, 0);
                    }
                    objectsVerified++;
                }
                continuationToken = page.nextContinuationToken();
            } while (continuationToken != null);
        }
        return objectsVerified;
    }

    private long deleteAllLegacyObjects(List<Long> userIds) {
        long objectsDeleted = 0;
        for (Long userId : userIds) {
            String prefix = legacyPrefix(userId);
            while (true) {
                List<S3Object> objects = list(prefix, null).contents();
                if (objects.isEmpty()) {
                    break;
                }
                if (objects.size() > DELETE_BATCH_SIZE) {
                    throw aborted("delete batch 상한 초과", 0, objectsDeleted, objects.size());
                }
                List<ObjectIdentifier> identifiers = objects.stream()
                        .map(object -> ObjectIdentifier.builder().key(object.key()).build())
                        .toList();
                DeleteObjectsResponse response = s3Client.deleteObjects(DeleteObjectsRequest.builder()
                        .bucket(bucket)
                        .delete(Delete.builder().objects(identifiers).quiet(false).build())
                        .build());
                Set<String> requestedKeys = new HashSet<>(objects.stream()
                        .map(S3Object::key)
                        .toList());
                Set<String> deletedKeys = new HashSet<>(response.deleted().stream()
                        .map(DeletedObject::key)
                        .toList());
                if (!response.errors().isEmpty() || !deletedKeys.equals(requestedKeys)) {
                    Set<String> mismatchedKeys = new HashSet<>(requestedKeys);
                    mismatchedKeys.removeAll(deletedKeys);
                    Set<String> unexpectedKeys = new HashSet<>(deletedKeys);
                    unexpectedKeys.removeAll(requestedKeys);
                    throw aborted("S3 delete 응답 불일치", 0, objectsDeleted,
                            response.errors().size() + mismatchedKeys.size()
                                    + unexpectedKeys.size());
                }
                objectsDeleted += identifiers.size();
            }
        }
        return objectsDeleted;
    }

    private long countLegacyObjects(List<Long> userIds) {
        long count = 0;
        for (Long userId : userIds) {
            String continuationToken = null;
            do {
                ListObjectsV2Response page = list(legacyPrefix(userId), continuationToken);
                count += page.contents().size();
                continuationToken = page.nextContinuationToken();
            } while (continuationToken != null);
        }
        return count;
    }

    private ListObjectsV2Response list(String prefix, String continuationToken) {
        return s3Client.listObjectsV2(ListObjectsV2Request.builder()
                .bucket(bucket)
                .prefix(prefix)
                .maxKeys(DELETE_BATCH_SIZE)
                .continuationToken(continuationToken)
                .build());
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

    private static String legacyPrefix(long userId) {
        return PhotoObjectKeys.sha256hex(userId) + "/photos/";
    }

    private static String subjectPrefix(UUID subjectId) {
        return PhotoObjectKeys.subjectNamespace(subjectId) + "/photos/";
    }

    private static PhotoMigrationAbortedException aborted(String reason, long objectsVerified,
                                                           long objectsDeleted,
                                                           long objectsRemaining) {
        return new PhotoMigrationAbortedException("legacy object 삭제 검증 실패(" + reason + ")"
                + ": objectsVerified=" + objectsVerified
                + " objectsDeleted=" + objectsDeleted
                + " objectsRemaining=" + objectsRemaining);
    }

    private record ObjectSnapshot(Long contentLength, String contentType) {
        private ObjectSnapshot {
            Objects.requireNonNull(contentLength);
        }
    }

    /** 건수 전용 결과 — 식별자 값 없음. */
    record Result(long usersProcessed, long objectsVerified, long objectsDeleted,
                  long objectsRemaining) {
    }
}
