package com.laimory.server.timeline;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TaskTokensTest {

    @Test
    void generate_returnsOpaque256BitUrlSafeValueWithoutStagePrefix() {
        String token = TaskTokens.generate();

        assertThat(token)
                .matches("[A-Za-z0-9_-]{43}")
                .doesNotContain(".");
    }

    @Test
    void matches_comparesRawTokenWithStoredHash() {
        String token = TaskTokens.generate();

        assertThat(TaskTokens.matches(token, TaskTokens.hash(token))).isTrue();
        assertThat(TaskTokens.matches(TaskTokens.generate(), TaskTokens.hash(token))).isFalse();
    }
}
