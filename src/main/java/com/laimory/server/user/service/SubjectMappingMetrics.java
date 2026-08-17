package com.laimory.server.user.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** subject secret load와 mapping 작업을 식별자 없는 low-cardinality timer로 관측한다. */
@Component
@RequiredArgsConstructor
public class SubjectMappingMetrics {

    static final String SECRET_LOAD = "laimory.subject.secret.load";
    static final String MAPPING_OPERATION = "laimory.subject.mapping.operation";

    private final MeterRegistry meterRegistry;

    public Timer.Sample start() {
        return Timer.start(meterRegistry);
    }

    /**
     * 성공한 secret load의 latency만 기록한다(tag 없음). 실패 경로는 기록하지 않는다 — load 실패
     * 시 context가 기동하지 않아 Prometheus가 meter를 수집할 수 없고(죽은 관측), 실패 관측은
     * 기동 실패 로그와 deploy preflight가 담당한다.
     */
    public void recordSecretLoad(Timer.Sample sample) {
        sample.stop(Timer.builder(SECRET_LOAD)
                .description("Secrets Manager subject HMAC snapshot load duration (success only)")
                .register(meterRegistry));
    }

    public void recordMapping(Timer.Sample sample, String operation, String result) {
        sample.stop(Timer.builder(MAPPING_OPERATION)
                .description("Subject mapping create/lookup duration")
                .tag("operation", operation)
                .tag("result", result)
                .register(meterRegistry));
    }
}
