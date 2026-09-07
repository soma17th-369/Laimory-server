package com.laimory.server.terms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class TermVersionTest {

    @ParameterizedTest
    @ValueSource(strings = {"1.0", "1.9", "1.10", "12.3", "999999999999999999999999999999.0"})
    void parse_acceptsCanonicalMajorMinor(String value) {
        assertThat(TermVersion.parse(value).toString()).isEqualTo(value);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "1", "01.0", "1.01", "v1.0", "1.0.0", "0.1", "1.-1",
            "1.10\n", "1.10\r", "1.10\r\n", "1.10\u0085", "1.10\u2028", "1.10\u2029",
            "1.10\f", "1.10\u000B"})
    void parse_rejectsNonCanonicalValues(String value) {
        assertThatThrownBy(() -> TermVersion.parse(value)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.9,1.10", "1.99,2.0", "999999999999999999.0,1000000000000000000.0"})
    void compareTo_usesNumericSegments(String pair) {
        List<TermVersion> versions = Arrays.stream(pair.split(",")).map(TermVersion::parse).toList();
        assertThat(versions.get(0)).isLessThan(versions.get(1));
    }
}
