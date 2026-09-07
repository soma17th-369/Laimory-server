package com.laimory.server.terms.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.laimory.server.terms.TermType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TermDocumentTest {

    @ParameterizedTest
    @CsvSource({"1.9,1.10", "1.99,2.0", "999999999999999999.0,1000000000000000000.0",
            "1.999999999999999999,1.1000000000000000000"})
    void isNewerThan_comparesMajorThenMinorNumerically(String older, String newer) {
        assertThat(document(newer).isNewerThan(document(older))).isTrue();
        assertThat(document(older).isNewerThan(document(newer))).isFalse();
    }

    @Test
    void sameVersion_isNotNewer() {
        assertThat(document("1.10").isNewerThan(document("1.10"))).isFalse();
    }

    @Test
    void isNewerThan_acceptsMaximumLengthSegmentsWithoutOverflow() {
        assertThat(document("9".repeat(62) + ".0").isNewerThan(document("9".repeat(61) + ".99")))
                .isTrue();
        assertThat(document("1." + "9".repeat(62)).isNewerThan(document("1." + "9".repeat(61))))
                .isTrue();
    }

    private static TermDocument document(String version) {
        return TermDocument.of(TermType.TERMS_OF_SERVICE, version, "이용약관",
                "https://www.laimory.app/terms/terms-of-service/" + version);
    }
}
