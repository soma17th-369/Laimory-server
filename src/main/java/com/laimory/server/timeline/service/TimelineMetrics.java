package com.laimory.server.timeline.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * 타임라인 작성 흐름의 저카디널리티 업무 메트릭.
 *
 * <p>tag 값은 이 클래스가 소유한 고정 결과값만 사용한다. user/task/FID/좌표/URL/예외 메시지 같은
 * 요청별 값은 metric label로 전달하지 않는다.
 */
@Component
public class TimelineMetrics {

    // Prometheus/OpenMetrics의 예약 suffix "_created"와 충돌하지 않도록 명사형 creation을 쓴다.
    static final String DRAFT_CREATED = "laimory.timeline.draft.creation";
    static final String TERMINAL_TRANSITION = "laimory.timeline.task.terminal";
    static final String CALLBACK_DURATION = "laimory.timeline.callback.duration";

    private final Counter draftCreated;
    private final Counter terminalSuccess;
    private final Counter terminalFailed;
    private final Timer callbackDuration;
    private final MeterRegistry meterRegistry;

    public TimelineMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.draftCreated = Counter.builder(DRAFT_CREATED)
                .description("Successfully persisted timeline draft tasks")
                .register(meterRegistry);
        this.terminalSuccess = terminalCounter(meterRegistry, "success");
        this.terminalFailed = terminalCounter(meterRegistry, "failed");
        this.callbackDuration = Timer.builder(CALLBACK_DURATION)
                .description("Timeline AI callback handling duration")
                .register(meterRegistry);
    }

    private static Counter terminalCounter(MeterRegistry registry, String result) {
        return Counter.builder(TERMINAL_TRANSITION)
                .description("Successfully persisted timeline task terminal transitions")
                .tag("result", result)
                .register(registry);
    }

    public void recordDraftCreated() {
        draftCreated.increment();
    }

    public void recordTerminalSuccess() {
        terminalSuccess.increment();
    }

    public void recordTerminalFailed() {
        terminalFailed.increment();
    }

    public Timer.Sample startCallback() {
        return Timer.start(meterRegistry);
    }

    public void recordCallback(Timer.Sample sample) {
        sample.stop(callbackDuration);
    }
}
