package com.laimory.server.common.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class BuildInfoMetricsTest {

    @Test
    void exposesOnlyShortCommitOnDedicatedInfoGauge() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new BuildInfoMetrics("ABCDEF1234567890ABCDEF1234567890ABCDEF12").bindTo(registry);

        assertThat(registry.get(BuildInfoMetrics.BUILD_INFO)
                .tag("commit", "abcdef123456").gauge().value()).isEqualTo(1);
        assertThat(registry.get(BuildInfoMetrics.BUILD_INFO).gauge().getId().getTags())
                .extracting(tag -> tag.getKey())
                .containsOnly("commit");
    }

    @Test
    void normalizesLocalAndRejectsArbitraryLabelInput() {
        assertThat(BuildInfoMetrics.normalize(" local ")).isEqualTo("local");
        assertThat(BuildInfoMetrics.normalize("branch/name")).isEqualTo("unknown");
        assertThat(BuildInfoMetrics.normalize(null)).isEqualTo("unknown");
    }
}
