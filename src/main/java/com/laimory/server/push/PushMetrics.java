package com.laimory.server.push;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * FCM batch 응답이 확인한 저카디널리티 발송 결과.
 *
 * <p>차원은 고정 알림 계열과 결과뿐이다 — task/FID/error 원문은 label로 사용하지 않는다. 문구 때문에
 * 나뉜 종류들은 같은 {@link PushMessageType#metricGroup()}을 공유하므로 같은 counter에 합산된다.
 * sender 자체가 예외를 던져 결과를 모르는 경우에는 성공·실패 어느 쪽도 추측해서 기록하지 않는다.
 */
@Component
public class PushMetrics {

    static final String DELIVERY = "laimory.push.delivery";
    static final String PREFERENCE_MISSING = "laimory.push.preference.missing";

    private final Map<PushMessageType, Counters> countersByType = new EnumMap<>(PushMessageType.class);
    private final Counter preferenceMissing;

    public PushMetrics(MeterRegistry meterRegistry) {
        this.preferenceMissing = Counter.builder(PREFERENCE_MISSING)
                .description("Push master preference row was absent when an existing notification path read it")
                .register(meterRegistry);
        for (PushMessageType type : PushMessageType.values()) {
            countersByType.put(type, new Counters(
                    deliveryCounter(meterRegistry, type, "success"),
                    deliveryCounter(meterRegistry, type, "failed")));
        }
    }

    /** 같은 계열의 두 종류는 (name, tags)가 같아 Micrometer가 기존 counter를 그대로 돌려준다. */
    private static Counter deliveryCounter(MeterRegistry registry, PushMessageType type, String result) {
        return Counter.builder(DELIVERY)
                .description("Firebase Cloud Messaging delivery attempts reported by the batch response")
                .tag("type", type.metricGroup())
                .tag("result", result)
                .register(registry);
    }

    /**
     * 기존 정보성 발송 경로가 마스터 행 부재를 만나 기본값(ON)으로 해석한 횟수. rollout 공백이 언제
     * 닫히는지 보는 용도라 차원을 두지 않는다 — 0으로 수렴하지 않으면 backfill이 덜 끝난 것이다.
     */
    public void recordPreferenceMissing() {
        preferenceMissing.increment();
    }

    public void record(PushMessageType type, PushSendResult result) {
        Counters counters = countersByType.get(type);
        counters.success().increment(result.successCount());
        counters.failed().increment(result.failureCount());
    }

    private record Counters(Counter success, Counter failed) {
    }
}
