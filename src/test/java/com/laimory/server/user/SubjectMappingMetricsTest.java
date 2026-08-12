package com.laimory.server.user;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

/** subject metric은 고정 tag만 사용한다 — mapping timer count가 결과별 건수를, secret load는 무tag 성공 latency만 제공한다. */
class SubjectMappingMetricsTest {

    @Test
    void recordsSecretLoadAndMappingOperationsWithOnlyBoundedTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SubjectMappingMetrics metrics = new SubjectMappingMetrics(registry);

        Timer.Sample secretLoad = metrics.start();
        metrics.recordSecretLoad(secretLoad);
        Timer.Sample create = metrics.start();
        metrics.recordMapping(create, "create", "success");
        Timer.Sample missing = metrics.start();
        metrics.recordMapping(missing, "lookup", "missing");

        // secret load는 성공 경로 전용·tag 없음 — 실패 시 context 미기동으로 scrape 불가한 죽은
        // 관측이라 result tag 자체를 두지 않는다(실패 관측은 기동 실패 로그·deploy preflight 담당).
        assertThat(registry.get(SubjectMappingMetrics.SECRET_LOAD).timer().count()).isEqualTo(1);
        assertThat(registry.get(SubjectMappingMetrics.SECRET_LOAD).timer().getId().getTags()).isEmpty();
        assertThat(registry.get(SubjectMappingMetrics.MAPPING_OPERATION)
                .tag("operation", "create").tag("result", "success").timer().count()).isEqualTo(1);
        assertThat(registry.get(SubjectMappingMetrics.MAPPING_OPERATION)
                .tag("operation", "lookup").tag("result", "missing").timer().count()).isEqualTo(1);

        registry.getMeters().forEach(meter -> assertThat(meter.getId().getTags())
                .allSatisfy(tag -> assertThat(tag.getKey()).isIn("operation", "result")));
    }
}
