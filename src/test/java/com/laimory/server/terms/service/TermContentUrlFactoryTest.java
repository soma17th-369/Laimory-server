package com.laimory.server.terms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.laimory.server.terms.TermType;
import java.net.URI;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 약관 원문 page URL 생성·base URL 기동 검증 단위 테스트. 5종 slug와 결합한 실제 URL 문자열을 고정하고
 * (공개 계약이라 slug 변경이 조용히 통과하면 안 된다), 잘못된 base 설정이 첫 요청 5xx가 아니라 기동
 * 실패로 수렴하는지 확인한다. 인프라 0.
 */
class TermContentUrlFactoryTest {

    private static final String BASE_URL = "https://laimory.app/terms";

    @ParameterizedTest
    @CsvSource({
            "TERMS_OF_SERVICE, https://laimory.app/terms/terms-of-service/1.0",
            "PRIVACY_POLICY, https://laimory.app/terms/privacy-policy/1.0",
            "SENSITIVE_INFORMATION_CONSENT, https://laimory.app/terms/sensitive-information-consent/1.0",
            "THIRD_PARTY_PROVISION_CONSENT, https://laimory.app/terms/third-party-provision-consent/1.0",
            "CROSS_BORDER_TRANSFER_CONSENT, https://laimory.app/terms/cross-border-transfer-consent/1.0"
    })
    void create_buildsPublishedPageUrlPerTermType(TermType termType, String expected) {
        assertThat(factory().create(termType, "1.0")).isEqualTo(URI.create(expected));
    }

    @Test
    void create_coversEveryTermType() {
        // 새 종류가 추가되면 위 표도 함께 늘어나야 한다 — slug 누락이 조용히 통과하지 않게 한다.
        assertThat(Arrays.stream(TermType.values()).map(TermType::contentSlug).distinct().count())
                .isEqualTo(TermType.values().length);
    }

    @Test
    void create_keepsVersionDotsUnescaped() {
        // MAJOR.MINOR는 문자열 식별자다 — page 이름 규칙이므로 점을 인코딩하면 URL이 달라진다.
        assertThat(factory().create(TermType.PRIVACY_POLICY, "1.10"))
                .isEqualTo(URI.create("https://laimory.app/terms/privacy-policy/1.10"));
    }

    @ParameterizedTest
    @CsvSource({
            "1/0, https://laimory.app/terms/privacy-policy/1%2F0",
            "1 0, https://laimory.app/terms/privacy-policy/1%200",
            "../1.0, https://laimory.app/terms/privacy-policy/..%2F1.0"
    })
    void create_keepsVersionInsideASinglePathSegment(String version, String expected) {
        // version은 opaque segment다 — 구분자가 섞여도 다른 page를 가리키는 URL로 새지 않는다.
        assertThat(factory().create(TermType.PRIVACY_POLICY, version)).isEqualTo(URI.create(expected));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "   ",                                  // blank
            "laimory.app/terms",                    // relative
            "/terms",                               // relative
            "http://laimory.app/terms",             // http
            "https://dev.laimory.app/terms",        // host 불일치
            "https://laimory.app.evil.com/terms",   // host 불일치
            "https://laimory.app:443/terms",        // port
            "https://user@laimory.app/terms",       // userinfo
            "https://laimory.app/terms?v=1",        // query
            "https://laimory.app/terms#top",        // fragment
            "https://laimory.app/terms/",           // trailing slash
            "https://laimory.app",                  // path 없음
            "https://laimory.app/legal"             // path 불일치
    })
    void constructor_failsFastOnInvalidBaseUrl(String baseUrl) {
        assertThatThrownBy(() -> new TermContentUrlFactory(baseUrl))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.terms.public-base-url");
    }

    private static TermContentUrlFactory factory() {
        return new TermContentUrlFactory(BASE_URL);
    }
}
