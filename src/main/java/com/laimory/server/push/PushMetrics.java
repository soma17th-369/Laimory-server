package com.laimory.server.push;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * FCM batch 응답이 확인한 저카디널리티 발송 결과.
 *
 * <p>task/FID/error 원문은 label로 사용하지 않는다. sender 자체가 예외를 던져 결과를 모르는 경우에는
 * 성공·실패 어느 쪽도 추측해서 기록하지 않는다.
 */
@Component
public class PushMetrics {

    static final String DELIVERY = "laimory.push.delivery";

    private final Counter success;
    private final Counter failed;

    public PushMetrics(MeterRegistry meterRegistry) {
        this.success = deliveryCounter(meterRegistry, "success");
        this.failed = deliveryCounter(meterRegistry, "failed");
    }

    private static Counter deliveryCounter(MeterRegistry registry, String result) {
        return Counter.builder(DELIVERY)
                .description("Firebase Cloud Messaging delivery attempts reported by the batch response")
                .tag("result", result)
                .register(registry);
    }

    public void record(PushSendResult result) {
        success.increment(result.successCount());
        failed.increment(result.failureCount());
    }
}
