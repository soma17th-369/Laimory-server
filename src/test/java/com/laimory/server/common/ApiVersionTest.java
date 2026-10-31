package com.laimory.server.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class ApiVersionTest {

    @Test
    void comparesByNumberNotByString() {
        assertThat(ApiVersion.of("v1").isLowerThan(ApiVersion.of("v2"))).isTrue();
        assertThat(ApiVersion.of("v2").isLowerThan(ApiVersion.of("v2"))).isFalse();
        assertThat(ApiVersion.of("v10").isLowerThan(ApiVersion.of("v2"))).isFalse();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "1", "v", "V1", "v1.0", "v99999999999"})
    void rejectsValuesOutsideTheVersionFormat(String value) {
        assertThatThrownBy(() -> ApiVersion.of(value)).isInstanceOf(IllegalArgumentException.class);
    }
}
