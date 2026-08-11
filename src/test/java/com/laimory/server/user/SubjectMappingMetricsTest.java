package com.laimory.server.user;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

/** subject metric은 고정 tag만 사용하고 timer count로 성공·실패 건수를 함께 제공한다. */
class SubjectMappingMetricsTest {

    @Test
    void recordsSecretLoadAndMappingOperationsWithOnlyBoundedTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SubjectMappingMetrics metrics = new SubjectMappingMetrics(registry);

        Timer.Sample secretSuccess = metrics.start();
        metrics.recordSecretLoad(secretSuccess, "success");
        Timer.Sample secretFailure = metrics.start();
        metrics.recordSecretLoad(secretFailure, "failed");
        Timer.Sample create = metrics.start();
        metrics.recordMapping(create, "create", "success");
        Timer.Sample missing = metrics.start();
        metrics.recordMapping(missing, "lookup", "missing");

        assertThat(registry.get(SubjectMappingMetrics.SECRET_LOAD)
                .tag("result", "success").timer().count()).isEqualTo(1);
        assertThat(registry.get(SubjectMappingMetrics.SECRET_LOAD)
                .tag("result", "failed").timer().count()).isEqualTo(1);
        assertThat(registry.get(SubjectMappingMetrics.MAPPING_OPERATION)
                .tag("operation", "create").tag("result", "success").timer().count()).isEqualTo(1);
        assertThat(registry.get(SubjectMappingMetrics.MAPPING_OPERATION)
                .tag("operation", "lookup").tag("result", "missing").timer().count()).isEqualTo(1);

        registry.getMeters().forEach(meter -> assertThat(meter.getId().getTags())
                .allSatisfy(tag -> assertThat(tag.getKey()).isIn("operation", "result")));
    }
}
