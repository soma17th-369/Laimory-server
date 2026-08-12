package com.laimory.server.timeline.photo.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.laimory.server.common.id.SubjectId;
import com.laimory.server.timeline.photo.PhotoObjectKeys;
import com.laimory.server.user.SubjectMappingService;
import com.laimory.server.user.UserRepository;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.DeletedObject;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.S3Error;

class LegacyPhotoObjectDeleteMigrationTest {

    private static final String BUCKET = "test-photo-bucket";
    private static final long USER_ID = 7L;
    private static final long OTHER_USER_ID = 8L;

    private final S3Client s3Client = mock(S3Client.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final SubjectMappingService subjectMappingService = mock(SubjectMappingService.class);
    private final SubjectId subject = subjectIdOf("3f2504e0-4f89-41d3-9a0c-0305e82c3301");
    private final SubjectId otherSubject = subjectIdOf("3f2504e0-4f89-41d3-9a0c-0305e82c3302");
    private final Map<String, StoredObject> bucketContents = new HashMap<>();

    private final LegacyPhotoObjectDeleteMigration migration =
            new LegacyPhotoObjectDeleteMigration(s3Client, BUCKET, userRepository,
                    subjectMappingService);

    private record StoredObject(long size, String contentType) {
    }

    @BeforeEach
    void stubS3() {
        when(userRepository.findAllUserIds()).thenReturn(List.of(USER_ID));
        when(subjectMappingService.getRequired(USER_ID)).thenReturn(subject);
        when(subjectMappingService.getRequired(OTHER_USER_ID)).thenReturn(otherSubject);
        when(s3Client.listObjectsV2(any(ListObjectsV2Request.class))).thenAnswer(invocation -> {
            ListObjectsV2Request request = invocation.getArgument(0);
            List<S3Object> contents = bucketContents.entrySet().stream()
                    .filter(entry -> entry.getKey().startsWith(request.prefix()))
                    .limit(request.maxKeys())
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
        when(s3Client.deleteObjects(any(DeleteObjectsRequest.class))).thenAnswer(invocation -> {
            DeleteObjectsRequest request = invocation.getArgument(0);
            List<DeletedObject> deleted = request.delete().objects().stream()
                    .map(identifier -> {
                        bucketContents.remove(identifier.key());
                        return DeletedObject.builder().key(identifier.key()).build();
                    })
                    .toList();
            return DeleteObjectsResponse.builder().deleted(deleted).build();
        });
    }

    @Test
    void deleteLegacy_verifiesTargetsThenDeletesEveryKnownUserSource() {
        putMatchingPair(USER_ID, subject, "a.jpg", 10, "image/jpeg");
        putMatchingPair(USER_ID, subject, "b.png", 20, "image/png");

        LegacyPhotoObjectDeleteMigration.Result result = migration.execute();

        assertThat(result).isEqualTo(new LegacyPhotoObjectDeleteMigration.Result(1, 2, 2, 0));
        assertThat(bucketContents)
                .doesNotContainKeys(legacyKey(USER_ID, "a.jpg"), legacyKey(USER_ID, "b.png"))
                .containsKeys(subjectKey(subject, "a.jpg"), subjectKey(subject, "b.png"));
    }

    @Test
    void deleteLegacy_targetMismatchForLaterUser_abortsBeforeAnyDelete() {
        when(userRepository.findAllUserIds()).thenReturn(List.of(USER_ID, OTHER_USER_ID));
        putMatchingPair(USER_ID, subject, "a.jpg", 10, "image/jpeg");
        bucketContents.put(legacyKey(OTHER_USER_ID, "b.jpg"),
                new StoredObject(20, "image/jpeg"));
        bucketContents.put(subjectKey(otherSubject, "b.jpg"),
                new StoredObject(21, "image/jpeg"));

        assertThatThrownBy(migration::execute)
                .isInstanceOf(PhotoMigrationAbortedException.class)
                .hasMessageContaining("target 동등성 불일치")
                .satisfies(LegacyPhotoObjectDeleteMigrationTest::assertMessageHasNoIdentifiers);

        verify(s3Client, never()).deleteObjects(any(DeleteObjectsRequest.class));
        assertThat(bucketContents).containsKey(legacyKey(USER_ID, "a.jpg"));
    }

    @Test
    void deleteLegacy_deleteResponseError_abortsWithoutLoggingIdentifiers() {
        putMatchingPair(USER_ID, subject, "a.jpg", 10, "image/jpeg");
        when(s3Client.deleteObjects(any(DeleteObjectsRequest.class)))
                .thenReturn(DeleteObjectsResponse.builder()
                        .errors(S3Error.builder().key(legacyKey(USER_ID, "a.jpg"))
                                .code("AccessDenied").build())
                        .build());

        assertThatThrownBy(migration::execute)
                .isInstanceOf(PhotoMigrationAbortedException.class)
                .hasMessageContaining("S3 delete 응답 불일치")
                .satisfies(LegacyPhotoObjectDeleteMigrationTest::assertMessageHasNoIdentifiers);
    }

    private void putMatchingPair(long userId, SubjectId subjectId, String filename, long size,
                                 String contentType) {
        StoredObject object = new StoredObject(size, contentType);
        bucketContents.put(legacyKey(userId, filename), object);
        bucketContents.put(subjectKey(subjectId, filename), object);
    }

    private static String legacyKey(long userId, String filename) {
        return PhotoObjectKeys.sha256hex(userId) + "/photos/" + filename;
    }

    private static String subjectKey(SubjectId subjectId, String filename) {
        return PhotoObjectKeys.subjectNamespace(subjectId) + "/photos/" + filename;
    }

    private static SubjectId subjectIdOf(String uuidLiteral) {
        UUID uuid = UUID.fromString(uuidLiteral);
        return SubjectId.fromBytes(ByteBuffer.allocate(16)
                .putLong(uuid.getMostSignificantBits())
                .putLong(uuid.getLeastSignificantBits())
                .array());
    }

    private static void assertMessageHasNoIdentifiers(Throwable thrown) {
        assertThat(thrown.getMessage())
                .doesNotContain(Long.toString(USER_ID) + "/")
                .doesNotContain(PhotoObjectKeys.sha256hex(USER_ID))
                .doesNotContain(PhotoObjectKeys.subjectNamespace(
                        subjectIdOf("3f2504e0-4f89-41d3-9a0c-0305e82c3301")))
                .doesNotContain("a.jpg")
                .doesNotContain("b.jpg");
    }
}
