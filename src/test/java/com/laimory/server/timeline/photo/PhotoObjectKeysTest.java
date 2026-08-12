package com.laimory.server.timeline.photo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.laimory.server.common.id.SubjectId;
import java.nio.ByteBuffer;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** PhotoObjectKeys 단위 검증(파일명 생성/확장자 매핑/legacy·subject 전체 key/namespace). 인프라 0. */
class PhotoObjectKeysTest {

    /** subject namespace fixture용 고정 UUIDv4(variant 2). */
    private static final String SUBJECT_UUID = "3f2504e0-4f89-41d3-9a0c-0305e82c3301";

    /** 독립 계산 벡터: SHA-256(위 UUID의 canonical 16 bytes) — Python hashlib로 별도 산출. */
    private static final String SUBJECT_NAMESPACE_VECTOR =
            "6704e007afafc2009ab9ddf89cbfa0e6b2c10564dde8eeff52589275ff810c15";

    /** 잘못된 입력 규칙 대조용: SHA-256(UUID '문자열' UTF-8) — 문자열을 해시하면 나오는 값. */
    private static final String SHA256_OF_UUID_STRING =
            "16362f566387b3cf5a6e92fb0a986c76ca20eb3a0c12cbdfbd0b29501e0c18df";

    private static SubjectId subjectIdOf(String uuidLiteral) {
        UUID uuid = UUID.fromString(uuidLiteral);
        return SubjectId.fromBytes(ByteBuffer.allocate(16)
                .putLong(uuid.getMostSignificantBits())
                .putLong(uuid.getLeastSignificantBits())
                .array());
    }

    @Test
    void newFilename_mapsContentTypeToExtensionWithUuidV7() {
        assertFilename(PhotoObjectKeys.newFilename("image/jpeg"), "jpg");
        assertFilename(PhotoObjectKeys.newFilename("image/png"), "png");
        assertFilename(PhotoObjectKeys.newFilename("image/webp"), "webp");
    }

    private static void assertFilename(String filename, String expectedExt) {
        assertThat(filename).endsWith("." + expectedExt);
        String base = filename.substring(0, filename.length() - (expectedExt.length() + 1));
        UUID parsed = UUID.fromString(base); // uuidv7이 파싱 가능한 정식 UUID인지
        assertThat(parsed.version()).isEqualTo(7);
        assertThat(parsed.variant()).isEqualTo(2);
    }

    @Test
    void newFilename_unknownContentType_throws() {
        assertThatThrownBy(() -> PhotoObjectKeys.newFilename("image/gif"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PhotoObjectKeys.newFilename("application/octet-stream"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void newFilename_nullOrBlankContentType_throwsIllegalArgument() {
        // null/blank이 switch에서 NPE(→500)가 아니라 IllegalArgumentException(→400)으로 정규화되는지
        assertThatThrownBy(() -> PhotoObjectKeys.newFilename(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PhotoObjectKeys.newFilename(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PhotoObjectKeys.newFilename("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fullKey_hasNoDateFolderAndUsesSha256Directory() {
        String filename = "0190f8b2-3c4d-7e5f-8a9b-0c1d2e3f4a5b.jpg";

        String key = PhotoObjectKeys.fullKey(filename, 0L);

        assertThat(key).isEqualTo(PhotoObjectKeys.sha256hex(0L) + "/photos/" + filename);
    }

    @Test
    void sha256hex_is64CharLowercaseHexAndStable() {
        String a = PhotoObjectKeys.sha256hex(0L);
        String b = PhotoObjectKeys.sha256hex(0L);

        assertThat(a).hasSize(64);
        assertThat(a).matches("[0-9a-f]{64}");
        assertThat(a).isEqualTo(b); // 안정적
        // 알려진 벡터: sha256("0")
        assertThat(a).isEqualTo("5feceb66ffc86f38d952786c6d696c79c2dbc239dd4e91b46729d73a27fb57e9");
        assertThat(PhotoObjectKeys.sha256hex(1L)).isNotEqualTo(a);
    }

    @Test
    void subjectNamespace_matchesIndependentVectorOfCanonical16Bytes() {
        String namespace = PhotoObjectKeys.subjectNamespace(subjectIdOf(SUBJECT_UUID));

        // 입력은 canonical 16바이트다 — 독립 계산한 SHA-256(bytes) 벡터와 정확 일치하고,
        // 문자열 UUID를 해시한 값과는 달라야 한다(문자열 표기 입력 금지 규칙의 대조군).
        assertThat(namespace).isEqualTo(SUBJECT_NAMESPACE_VECTOR);
        assertThat(namespace).isNotEqualTo(SHA256_OF_UUID_STRING);
        assertThat(namespace).matches("[0-9a-f]{64}");
    }

    @Test
    void subjectNamespace_stableForSameSubjectAndDiffersAcrossSubjects() {
        SubjectId subject = subjectIdOf(SUBJECT_UUID);
        SubjectId other = subjectIdOf("9b2e8f1a-6c3d-4b7e-8f1a-2c3d4e5f6a7b");

        assertThat(PhotoObjectKeys.subjectNamespace(subject))
                .isEqualTo(PhotoObjectKeys.subjectNamespace(subject));
        assertThat(PhotoObjectKeys.subjectNamespace(other))
                .isNotEqualTo(PhotoObjectKeys.subjectNamespace(subject));
    }

    @Test
    void subjectFullKey_usesSubjectNamespaceWithoutExposingIdentifiers() {
        String filename = "0190f8b2-3c4d-7e5f-8a9b-0c1d2e3f4a5b.jpg";
        SubjectId subject = subjectIdOf(SUBJECT_UUID);

        String key = PhotoObjectKeys.subjectFullKey(filename, subject);

        assertThat(key).isEqualTo(SUBJECT_NAMESPACE_VECTOR + "/photos/" + filename);
        // key에 subject UUID literal(대시 유무 무관)이 노출되지 않는다.
        assertThat(key).doesNotContain(SUBJECT_UUID);
        assertThat(key).doesNotContain(SUBJECT_UUID.replace("-", ""));
    }
}
