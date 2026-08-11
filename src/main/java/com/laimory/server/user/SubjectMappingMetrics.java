package com.laimory.server.user;

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

    public void recordSecretLoad(Timer.Sample sample, String result) {
        sample.stop(Timer.builder(SECRET_LOAD)
                .description("Secrets Manager subject HMAC snapshot load duration")
                .tag("result", result)
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
