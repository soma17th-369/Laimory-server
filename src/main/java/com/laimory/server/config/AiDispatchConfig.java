package com.laimory.server.config;

import com.laimory.server.aidispatch.DispatchMessageHandler;
import com.laimory.server.aidispatch.DispatchQueue;
import com.laimory.server.aidispatch.DispatchQueueWorker;
import com.laimory.server.aidispatch.Sleeper;
import com.laimory.server.aidispatch.SqsDispatchQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * AI 접수 대기열 배선(#515).
 *
 * <ul>
 *   <li>세션 생성 limiter는 항상 있다 — User Memory 배치가 큐 설정과 무관하게 쓴다.</li>
 *   <li>SQS 대기열은 TIMELINE route가 {@code queue}이거나 worker가 켜졌을 때만 만든다. 그때 큐 URL이 필수이며
 *       누락·형식 오류·리전 불일치는 기동 실패다.</li>
 *   <li>worker는 {@code app.ai.dispatch.worker.enabled=true}일 때만 돈다. 적재(route)와 소비(worker)를 따로 켜고 끈다 —
 *       롤백은 route를 inline으로 돌린 뒤 worker가 남은 메시지를 비우고 나서 worker를 끈다.</li>
 * </ul>
 *
 * <p>자격증명은 AWS SDK 기본 체인(EC2 인스턴스 프로파일)이고 별도 key property를 두지 않는다.
 */
@Configuration
public class AiDispatchConfig {

    @Bean
    Sleeper aiDispatchSleeper() {
        return Sleeper.SYSTEM;
    }

    @Bean
    @ConditionalOnProperty(name = "app.ai.dispatch.worker.enabled", havingValue = "true")
    DispatchQueueWorker timelineDispatchQueueWorker(DispatchQueue timelineDispatchQueue,
                                                    DispatchMessageHandler timelineQueuedDispatchHandler,
                                                    @Value("${app.ai.dispatch.worker.slots:16}") int slots,
                                                    Sleeper sleeper) {
        return new DispatchQueueWorker(timelineDispatchQueue, timelineQueuedDispatchHandler, slots, sleeper);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnExpression("'${app.ai.dispatch.timeline.route:inline}' == 'queue' "
            + "or '${app.ai.dispatch.worker.enabled:false}' == 'true'")
    static class TimelineDispatchQueueConfig {

        /** SQS queue URL — 리전 segment는 {@code aws.region}과 대조한다. */
        private static final Pattern QUEUE_URL =
                Pattern.compile("^https://sqs\\.([a-z0-9-]+)\\.amazonaws\\.com/[0-9]{12}/[A-Za-z0-9_-]{1,80}$");

        @Bean
        SqsClient aiDispatchSqsClient(@Value("${aws.region:ap-northeast-2}") String region) {
            return SqsClient.builder().region(Region.of(region)).build();
        }

        @Bean
        DispatchQueue timelineDispatchQueue(SqsClient aiDispatchSqsClient,
                                            @Value("${app.ai.dispatch.queue.timeline-url:}") String queueUrl,
                                            @Value("${aws.region:ap-northeast-2}") String region) {
            return new SqsDispatchQueue(aiDispatchSqsClient, requireQueueUrl(queueUrl, region));
        }

        static String requireQueueUrl(String queueUrl, String region) {
            if (queueUrl == null || queueUrl.isBlank()) {
                throw new IllegalStateException("app.ai.dispatch.queue.timeline-url은 TIMELINE route=queue 또는 "
                        + "worker 사용 시 필수입니다(APP_AI_DISPATCH_QUEUE_TIMELINE_URL 미주입).");
            }
            String trimmed = queueUrl.trim();
            Matcher matcher = QUEUE_URL.matcher(trimmed);
            if (!matcher.matches()) {
                throw new IllegalStateException("app.ai.dispatch.queue.timeline-url은 SQS queue URL이어야 합니다"
                        + "(https://sqs.<region>.amazonaws.com/<account>/<queue>).");
            }
            if (!matcher.group(1).equals(region)) {
                // client는 aws.region으로 만들어지므로 다른 리전 큐면 호출이 반드시 실패한다(설정 오류를 기동에서 잡는다).
                throw new IllegalStateException("app.ai.dispatch.queue.timeline-url의 region(" + matcher.group(1)
                        + ")이 aws.region(" + region + ")과 다릅니다.");
            }
            return trimmed;
        }
    }
}
