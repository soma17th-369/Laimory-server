package com.laimory.server.timeline.photo.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.laimory.server.common.id.SubjectId;
import com.laimory.server.timeline.photo.PhotoObjectKeys;
import com.laimory.server.timeline.repository.TimelinePhotoDeleteJobRepository;
import com.laimory.server.user.SubjectMappingService;
import com.laimory.server.user.UserRepository;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CopyObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * legacy→subject S3 object copy·검증 도구 단위 검증(mock S3) — delete job preflight fail-closed,
 * copy/head 호출·검증(크기+contentType), 멱등 skip, contentType drift 재복사 교정, 크기·copy 후
 * 검증 불일치 fail-closed, 예외 메시지 무식별자(contentType 값 포함).
 */
class PhotoObjectCopyMigrationTest {

    private static final String BUCKET = "test-photo-bucket";
    private static final String SUBJECT_UUID = "3f2504e0-4f89-41d3-9a0c-0305e82c3301";
    private static final long USER_ID = 7L;

    private final S3Client s3Client = mock(S3Client.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final SubjectMappingService subjectMappingService = mock(SubjectMappingService.class);
    private final TimelinePhotoDeleteJobRepository photoDeleteJobRepository =
            mock(TimelinePhotoDeleteJobRepository.class);

    private final PhotoObjectCopyMigration migration = new PhotoObjectCopyMigration(
            s3Client, BUCKET, userRepository, subjectMappingService, photoDeleteJobRepository);

    private final SubjectId subject = subjectIdOf(SUBJECT_UUID);
    private final String legacyPrefix = PhotoObjectKeys.sha256hex(USER_ID) + "/photos/";
    private final String subjectPrefix = PhotoObjectKeys.subjectNamespace(subject) + "/photos/";

    /** mock 버킷 상태(key → size·contentType). listObjectsV2/headObject/copyObject 스텁이 공유한다. */
    private final Map<String, StoredObject> bucketContents = new HashMap<>();

    /** mock S3 object metadata — CopyObject 기본(MetadataDirective=COPY)처럼 통째로 복제된다. */
    private record StoredObject(long size, String contentType) {
    }

    private static SubjectId subjectIdOf(String uuidLiteral) {
        UUID uuid = UUID.fromString(uuidLiteral);
        return SubjectId.fromBytes(ByteBuffer.allocate(16)
                .putLong(uuid.getMostSignificantBits())
                .putLong(uuid.getLeastSignificantBits())
                .array());
    }

    @BeforeEach
    void stubDefaults() {
        when(photoDeleteJobRepository.count()).thenReturn(0L);
        when(userRepository.findAllUserIds()).thenReturn(List.of(USER_ID));
        when(subjectMappingService.getRequired(USER_ID)).thenReturn(subject);
        when(s3Client.listObjectsV2(any(ListObjectsV2Request.class))).thenAnswer(invocation -> {
            ListObjectsV2Request request = invocation.getArgument(0);
            List<S3Object> contents = bucketContents.entrySet().stream()
                    .filter(entry -> entry.getKey().startsWith(request.prefix()))
                    .map(entry -> S3Object.builder()
                            .key(entry.getKey())
                            .size(entry.getValue().size())
                            .build())
                    .toList();
            return ListObjectsV2Response.builder().contents(contents).build();
        });
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenAnswer(invocation -> {
            HeadObjectRequest request = invocation.getArgument(0);
            StoredObject stored = bucketContents.get(request.key());
            if (stored == null) {
                throw NoSuchKeyException.builder().build();
            }
            return HeadObjectResponse.builder()
                    .contentLength(stored.size())
                    .contentType(stored.contentType())
                    .build();
        });
        when(s3Client.copyObject(any(CopyObjectRequest.class))).thenAnswer(invocation -> {
            CopyObjectRequest request = invocation.getArgument(0);
            bucketContents.put(request.destinationKey(), bucketContents.get(request.sourceKey()));
            return CopyObjectResponse.builder().build();
        });
    }

    @Test
    void pendingDeleteJobs_abortsBeforeAnyS3Call() {
        when(photoDeleteJobRepository.count()).thenReturn(3L);

        assertThatThrownBy(migration::execute)
                .isInstanceOf(PhotoMigrationAbortedException.class)
                .hasMessageContaining("pendingDeleteJobs=3");
        verifyNoInteractions(s3Client);
    }

    @Test
    void copyVerify_copiesEachLegacyObjectToSubjectKeyAndVerifiesSize() {
        bucketContents.put(legacyPrefix + "a.jpg", new StoredObject(10L, "image/jpeg"));
        bucketContents.put(legacyPrefix + "b.png", new StoredObject(20L, "image/png"));

        PhotoObjectCopyMigration.Result result = migration.execute();

        assertThat(result.usersProcessed()).isEqualTo(1);
        assertThat(result.objectsListed()).isEqualTo(2);
        assertThat(result.objectsCopied()).isEqualTo(2);
        assertThat(result.objectsAlreadyPresent()).isZero();
        ArgumentCaptor<CopyObjectRequest> copies = ArgumentCaptor.forClass(CopyObjectRequest.class);
        verify(s3Client, times(2)).copyObject(copies.capture());
        assertThat(copies.getAllValues())
                .allSatisfy(request -> {
                    assertThat(request.sourceBucket()).isEqualTo(BUCKET);
                    assertThat(request.destinationBucket()).isEqualTo(BUCKET);
                })
                .extracting(CopyObjectRequest::sourceKey, CopyObjectRequest::destinationKey)
                .containsExactlyInAnyOrder(
                        tuple(legacyPrefix + "a.jpg", subjectPrefix + "a.jpg"),
                        tuple(legacyPrefix + "b.png", subjectPrefix + "b.png"));
    }

    @Test
    void copyVerify_skipsObjectAlreadyCopiedWithSameSizeAndContentType() {
        bucketContents.put(legacyPrefix + "a.jpg", new StoredObject(10L, "image/jpeg"));
        bucketContents.put(legacyPrefix + "b.png", new StoredObject(20L, "image/png"));
        // 이전 실행이 이미 복사한 object — 크기·contentType 모두 일치
        bucketContents.put(subjectPrefix + "a.jpg", new StoredObject(10L, "image/jpeg"));

        PhotoObjectCopyMigration.Result result = migration.execute();

        assertThat(result.objectsListed()).isEqualTo(2);
        assertThat(result.objectsCopied()).isEqualTo(1);
        assertThat(result.objectsAlreadyPresent()).isEqualTo(1);
        ArgumentCaptor<CopyObjectRequest> copies = ArgumentCaptor.forClass(CopyObjectRequest.class);
        verify(s3Client).copyObject(copies.capture());
        assertThat(copies.getValue().sourceKey()).isEqualTo(legacyPrefix + "b.png");
    }

    @Test
    void copyVerify_existingTargetWithDifferentSize_failsClosedWithoutCopy() {
        bucketContents.put(legacyPrefix + "a.jpg", new StoredObject(10L, "image/jpeg"));
        // 크기 불일치 target
        bucketContents.put(subjectPrefix + "a.jpg", new StoredObject(99L, "image/jpeg"));

        assertThatThrownBy(migration::execute)
                .isInstanceOf(PhotoMigrationAbortedException.class)
                .hasMessageContaining("mismatches=1")
                .satisfies(PhotoObjectCopyMigrationTest::assertMessageHasNoIdentifiers);
        verify(s3Client, never()).copyObject(any(CopyObjectRequest.class));
    }

    @Test
    void copyVerify_existingTargetWithSameSizeDifferentContentType_recopiesAndVerifies() {
        bucketContents.put(legacyPrefix + "a.jpg", new StoredObject(10L, "image/jpeg"));
        // 크기는 같지만 contentType이 다른 기존 target — alreadyPresent skip이 아니라 재복사로 교정해야 한다.
        bucketContents.put(subjectPrefix + "a.jpg", new StoredObject(10L, "application/octet-stream"));

        PhotoObjectCopyMigration.Result result = migration.execute();

        assertThat(result.objectsListed()).isEqualTo(1);
        assertThat(result.objectsCopied()).isEqualTo(1);
        assertThat(result.objectsAlreadyPresent()).isZero();
        ArgumentCaptor<CopyObjectRequest> copies = ArgumentCaptor.forClass(CopyObjectRequest.class);
        verify(s3Client).copyObject(copies.capture());
        assertThat(copies.getValue().sourceKey()).isEqualTo(legacyPrefix + "a.jpg");
        assertThat(copies.getValue().destinationKey()).isEqualTo(subjectPrefix + "a.jpg");
        // 재복사가 source metadata로 target을 교정했다(CopyObject 기본 MetadataDirective=COPY).
        assertThat(bucketContents.get(subjectPrefix + "a.jpg"))
                .isEqualTo(new StoredObject(10L, "image/jpeg"));
    }

    @Test
    void copyVerify_verificationAfterCopyMismatch_failsClosed() {
        bucketContents.put(legacyPrefix + "a.jpg", new StoredObject(10L, "image/jpeg"));
        // copy가 성공 응답을 주지만 target 크기가 어긋나는 상황을 시뮬레이션한다.
        when(s3Client.copyObject(any(CopyObjectRequest.class))).thenAnswer(invocation -> {
            CopyObjectRequest request = invocation.getArgument(0);
            bucketContents.put(request.destinationKey(), new StoredObject(5L, "image/jpeg"));
            return CopyObjectResponse.builder().build();
        });

        assertThatThrownBy(migration::execute)
                .isInstanceOf(PhotoMigrationAbortedException.class)
                .hasMessageContaining("mismatches=1")
                .satisfies(PhotoObjectCopyMigrationTest::assertMessageHasNoIdentifiers);
    }

    @Test
    void copyVerify_contentTypeMismatchAfterCopy_failsClosedWithoutContentTypeValue() {
        bucketContents.put(legacyPrefix + "a.jpg", new StoredObject(10L, "image/jpeg"));
        // copy가 성공 응답을 주지만 target contentType이 보존되지 않는 상황을 시뮬레이션한다.
        when(s3Client.copyObject(any(CopyObjectRequest.class))).thenAnswer(invocation -> {
            CopyObjectRequest request = invocation.getArgument(0);
            bucketContents.put(request.destinationKey(),
                    new StoredObject(10L, "application/octet-stream"));
            return CopyObjectResponse.builder().build();
        });

        assertThatThrownBy(migration::execute)
                .isInstanceOf(PhotoMigrationAbortedException.class)
                .hasMessageContaining("mismatches=1")
                .satisfies(PhotoObjectCopyMigrationTest::assertMessageHasNoIdentifiers)
                // contentType 값 자체도 미출력 — 불일치 여부(건수)만 보고한다.
                .satisfies(thrown -> assertThat(thrown.getMessage())
                        .doesNotContain("image/jpeg")
                        .doesNotContain("application/octet-stream"));
    }

    @Test
    void copyVerify_sourceObjectGoneBetweenListAndHead_failsClosed() {
        // 목록에는 있으나 HEAD 시점에 사라진 source object — fail-closed 중단.
        when(s3Client.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(ListObjectsV2Response.builder()
                        .contents(S3Object.builder().key(legacyPrefix + "a.jpg").size(10L).build())
                        .build());

        assertThatThrownBy(migration::execute)
                .isInstanceOf(PhotoMigrationAbortedException.class)
                .hasMessageContaining("mismatches=1")
                .satisfies(PhotoObjectCopyMigrationTest::assertMessageHasNoIdentifiers);
        verify(s3Client, never()).copyObject(any(CopyObjectRequest.class));
    }

    @Test
    void missingSubjectMapping_propagatesWithoutIdentifierInMessage() {
        bucketContents.put(legacyPrefix + "a.jpg", new StoredObject(10L, "image/jpeg"));
        when(subjectMappingService.getRequired(USER_ID))
                .thenThrow(new IllegalStateException("subject mapping missing for authenticated user"));

        assertThatThrownBy(migration::execute)
                .isInstanceOf(IllegalStateException.class)
                .satisfies(PhotoObjectCopyMigrationTest::assertMessageHasNoIdentifiers);
    }

    /** fail-closed 예외 메시지에 raw userId·namespace hex·filename이 없어야 한다(건수만 허용). */
    private static void assertMessageHasNoIdentifiers(Throwable thrown) {
        SubjectId subject = subjectIdOf(SUBJECT_UUID);
        assertThat(thrown.getMessage())
                .doesNotContain(Long.toString(USER_ID) + "/") // 경로화된 userId
                .doesNotContain(PhotoObjectKeys.sha256hex(USER_ID))
                .doesNotContain(PhotoObjectKeys.subjectNamespace(subject))
                .doesNotContain(SUBJECT_UUID)
                .doesNotContain("a.jpg");
    }
}
