package com.laimory.server.timeline.photo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** PhotoObjectKeys 단위 검증(파일명 생성/확장자 매핑/전체 key/sha256hex). 인프라 0. */
class PhotoObjectKeysTest {

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
}
