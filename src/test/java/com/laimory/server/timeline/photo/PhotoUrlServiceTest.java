package com.laimory.server.timeline.photo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 비서명 CloudFront 서빙 URL 구성 단위 검증. 서명/만료 없이 {@code https://{domain}/{fullKey}} 형태의
 * 안정 URL을 그대로 만든다(legacy·subject 규칙 공존). 인프라 0.
 */
class PhotoUrlServiceTest {

    private static final String DOMAIN = "cdn.example.com";

    @Test
    void buildUrl_returnsUnsignedStableUrl() {
        PhotoUrlService service = new PhotoUrlService(DOMAIN);
        String filename = "0190f8b2-3c4d-7e5f-8a9b-0c1d2e3f4a5b.jpg";

        String url = service.buildUrl(filename, 0L);

        assertThat(url)
                .isEqualTo("https://" + DOMAIN + "/" + PhotoObjectKeys.sha256hex(0L) + "/photos/" + filename);
    }

    @Test
    void buildSubjectUrl_returnsUnsignedSubjectUrlWithoutExposingIdentifiers() {
        PhotoUrlService service = new PhotoUrlService(DOMAIN);
        String filename = "0190f8b2-3c4d-7e5f-8a9b-0c1d2e3f4a5b.jpg";
        String subjectUuid = "3f2504e0-4f89-41d3-9a0c-0305e82c3301";
        UUID subject = UUID.fromString(subjectUuid);

        String url = service.buildSubjectUrl(filename, subject);

        assertThat(url).isEqualTo("https://" + DOMAIN + "/"
                + PhotoObjectKeys.subjectNamespace(subject) + "/photos/" + filename);
        // URL에 subject UUID literal(대시 유무 무관)이 노출되지 않는다.
        assertThat(url).doesNotContain(subjectUuid);
        assertThat(url).doesNotContain(subjectUuid.replace("-", ""));
    }
}
