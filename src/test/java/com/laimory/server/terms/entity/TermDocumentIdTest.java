package com.laimory.server.terms.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.laimory.server.terms.TermType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class TermDocumentIdTest {

    @ParameterizedTest
    @ValueSource(strings = {"1.0", "1.9", "1.10", "12.3", "999999999999999999999999999999.0"})
    void keys_acceptCanonicalVersionWithoutChangingItsValue(String version) {
        assertThat(new TermDocumentId(TermType.TERMS_OF_SERVICE, version).getVersion()).isEqualTo(version);
        assertThat(new TermAgreementId(7L, TermType.TERMS_OF_SERVICE, version).getVersion()).isEqualTo(version);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "1", "01.0", "1.01", "v1.0", "1.0.0", "0.1", "1.-1",
            "1.10\n", "1.10\r", "1.10\r\n", "1.10\u0085", "1.10\u2028", "1.10\u2029",
            "1.10\f", "1.10\u000B"})
    void keys_rejectNonCanonicalVersionBeforePersistence(String version) {
        assertThatThrownBy(() -> new TermDocumentId(TermType.TERMS_OF_SERVICE, version))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TermAgreementId(7L, TermType.TERMS_OF_SERVICE, version))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void versionLength_accepts64CharactersAndRejects65() {
        String maximumLength = "9".repeat(62) + ".0";
        assertThat(new TermDocumentId(TermType.TERMS_OF_SERVICE, maximumLength).getVersion())
                .hasSize(64);
        assertThatThrownBy(() -> new TermDocumentId(TermType.TERMS_OF_SERVICE, "9" + maximumLength))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TermAgreementId(7L, TermType.TERMS_OF_SERVICE, "9" + maximumLength))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
