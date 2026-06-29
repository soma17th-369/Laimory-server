package com.laimory.server.timeline.photo;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** filename 엄격 검증기 단위 테스트. UUIDv7+허용ext만 통과, 경로 조작·잘못된 형식은 거부. */
class PhotoFilenamesTest {

    @Test
    void accepts_uuidV7WithAllowedExtensions() {
        assertThatCode(() -> PhotoFilenames.requireValid("0190b2c3-d4e5-7f6a-8b9c-0d1e2f3a4b5c.jpg"))
                .doesNotThrowAnyException();
        assertThatCode(() -> PhotoFilenames.requireValid("0190b2c3-d4e5-7f6a-8b9c-0d1e2f3a4b5c.png"))
                .doesNotThrowAnyException();
        assertThatCode(() -> PhotoFilenames.requireValid("0190b2c3-d4e5-7f6a-8b9c-0d1e2f3a4b5c.webp"))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsFilenameProducedByPhotoObjectKeys() {
        assertThatCode(() -> PhotoFilenames.requireValid(PhotoObjectKeys.newFilename("image/jpeg")))
                .doesNotThrowAnyException();
    }

    @Test
    void rejects_null() {
        assertThatThrownBy(() -> PhotoFilenames.requireValid(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_pathTraversalAndSlashes() {
        assertThatThrownBy(() -> PhotoFilenames.requireValid("../etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PhotoFilenames.requireValid("a/b.jpg"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PhotoFilenames.requireValid("0190b2c3-d4e5-7f6a-8b9c-0d1e2f3a4b5c.jpg/.."))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_disallowedExtensionOrShape() {
        // gif 미허용
        assertThatThrownBy(() -> PhotoFilenames.requireValid("0190b2c3-d4e5-7f6a-8b9c-0d1e2f3a4b5c.gif"))
                .isInstanceOf(IllegalArgumentException.class);
        // 확장자 없음
        assertThatThrownBy(() -> PhotoFilenames.requireValid("0190b2c3-d4e5-7f6a-8b9c-0d1e2f3a4b5c"))
                .isInstanceOf(IllegalArgumentException.class);
        // UUID 버전 비트가 7이 아님(버전 4)
        assertThatThrownBy(() -> PhotoFilenames.requireValid("0190b2c3-d4e5-4f6a-8b9c-0d1e2f3a4b5c.jpg"))
                .isInstanceOf(IllegalArgumentException.class);
        // 대문자 hex 불허(소문자만)
        assertThatThrownBy(() -> PhotoFilenames.requireValid("0190B2C3-D4E5-7F6A-8B9C-0D1E2F3A4B5C.jpg"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
