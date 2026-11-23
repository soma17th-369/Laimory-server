package com.laimory.server.config;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * TIMELINE AI 접수 대기열의 SQS client(#515). TIMELINE route가 {@code queue}이거나 worker가 켜졌을 때만 만든다.
 *
 * <p>이 client를 쓰는 접수 경로({@code QueuedTimelineDispatchSubmitter})와 worker({@code TimelineDispatchQueueWorker})는
 * 같은 property의 큐 URL을 쓰므로 여기서 한 번 검증한다 — 누락·형식 오류·리전 불일치는 기동 실패다. 자격증명은 AWS SDK
 * 기본 체인(EC2 인스턴스 프로파일)이고 별도 key property를 두지 않는다.
 */
@Configuration
public class AiDispatchConfig {

    /** SQS queue URL — 리전 segment는 {@code aws.region}과 대조한다. */
    private static final Pattern QUEUE_URL =
            Pattern.compile("^https://sqs\\.([a-z0-9-]+)\\.amazonaws\\.com/[0-9]{12}/[A-Za-z0-9_-]{1,80}$");

    // 대소문자를 무시한다 — 접수 경로·worker 빈의 @ConditionalOnProperty(havingValue)와 같은 비교여야 "QUEUE"·"TRUE"에서
    // 경로 빈만 생기고 client가 빠지는 어긋남이 없다.
    @Bean
    @ConditionalOnExpression("'${app.ai.dispatch.timeline.route:inline}'.equalsIgnoreCase('queue') "
            + "or '${app.ai.dispatch.worker.enabled:false}'.equalsIgnoreCase('true')")
    SqsClient aiDispatchSqsClient(@Value("${app.ai.dispatch.queue.timeline-url:}") String queueUrl,
                                  @Value("${aws.region:ap-northeast-2}") String region) {
        requireQueueUrl(queueUrl, region);
        return SqsClient.builder().region(Region.of(region)).build();
    }

    static void requireQueueUrl(String queueUrl, String region) {
        if (queueUrl == null || queueUrl.isBlank()) {
            throw new IllegalStateException("app.ai.dispatch.queue.timeline-url은 TIMELINE route=queue 또는 "
                    + "worker 사용 시 필수입니다(APP_AI_DISPATCH_QUEUE_TIMELINE_URL 미주입).");
        }
        Matcher matcher = QUEUE_URL.matcher(queueUrl);
        if (!matcher.matches()) {
            throw new IllegalStateException("app.ai.dispatch.queue.timeline-url은 SQS queue URL이어야 합니다"
                    + "(https://sqs.<region>.amazonaws.com/<account>/<queue>).");
        }
        if (!matcher.group(1).equals(region)) {
            // client는 aws.region으로 만들어지므로 다른 리전 큐면 호출이 반드시 실패한다(설정 오류를 기동에서 잡는다).
            throw new IllegalStateException("app.ai.dispatch.queue.timeline-url의 region(" + matcher.group(1)
                    + ")이 aws.region(" + region + ")과 다릅니다.");
        }
    }
}
