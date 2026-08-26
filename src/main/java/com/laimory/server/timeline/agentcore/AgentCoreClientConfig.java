package com.laimory.server.timeline.agentcore;

import com.laimory.server.timeline.service.TimelineTaskService;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.awscore.retry.AwsRetryStrategy;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockagentcore.BedrockAgentCoreClient;

/**
 * AgentCore 접수 배선({@code app.ai.mode=agentcore} — #338). 환경 분기는 저장소 관례대로
 * {@code @ConditionalOnProperty}(mode property) 하나가 유일한 스위치다.
 *
 * <p>{@link BedrockAgentCoreClient}는 생성 시점에 AWS를 호출하지 않는다({@code PhotoStorageConfig}와
 * 같은 성질 — 자격증명·리전은 호출 시점 해석). 자격증명은 별도 property 없이 SDK 기본 체인
 * ({@code DefaultCredentialsProvider} — EC2 인스턴스 프로파일)으로 해석한다.
 *
 * <p><b>SDK 자동 재시도를 끄는 것이 이 설정의 핵심이다.</b> AI 계약상 우리 → AI 접수는 즉시 재시도하지
 * 않는데(ai-contract.md), SDK 기본 전략은 timeout·5xx·throttling에서 같은 payload를 재전송한다. 접수는
 * 멱등이 아니라 같은 {@code taskId}가 두 번 접수돼 AI가 중복 작업을 시작할 수 있으므로
 * {@link AwsRetryStrategy#doNotRetry()}로 봉인한다.
 *
 * <p>timeout은 HTTP mode의 접수 대기(connect 2s + read 5s)와 같은 크기로 고정한다 — 접수는 즉시 반환
 * 계약이고, 대기 상한이 task 수명(PROCESSING TTL 3분)의 절반에 근접하면 유효한 ack를 받고도 이미 만료된
 * taskId를 받게 된다. 이 불변식은 아래에서 기동 시 강제한다.
 */
@Configuration
@ConditionalOnProperty(name = "app.ai.mode", havingValue = "agentcore")
public class AgentCoreClientConfig {

    /** 전체 호출(SDK 재시도 없음이므로 사실상 단일 시도)의 상한. */
    static final Duration CALL_TIMEOUT = Duration.ofSeconds(7);
    /** 개별 시도의 상한. */
    static final Duration ATTEMPT_TIMEOUT = Duration.ofSeconds(5);

    /**
     * full Runtime ARN만 허용한다 — Runtime ID + account ID 조합은 대상이 애매해질 수 있어 쓰지 않는다
     * (이슈 #338 참고사항). region segment는 아래에서 {@code aws.region}과 대조한다.
     */
    private static final Pattern RUNTIME_ARN = Pattern.compile(
            "^arn:aws[a-zA-Z-]*:bedrock-agentcore:([a-z0-9-]+):[0-9]{12}:runtime/[A-Za-z0-9_.-]+$");

    /** endpoint(qualifier) 이름 — AgentCore 계약의 endpoint 이름 문자 집합. */
    private static final Pattern ENDPOINT = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_-]{0,99}$");

    /**
     * 기동 시 1회 검증한 설정 스냅샷. 누락·blank·형식 오류·리전 불일치는 전부 컨텍스트 기동 실패다 —
     * 첫 dispatch에서야 터지면 그 사이 사용자 요청이 UNKNOWN으로 쌓인다.
     */
    @Bean
    AgentCoreProperties agentCoreProperties(
            @Value("${app.ai.agentcore.runtime-arn:}") String runtimeArn,
            @Value("${app.ai.agentcore.endpoint:}") String endpoint,
            @Value("${aws.region:ap-northeast-2}") String region) {
        requireAckWaitWithinTaskLifetime();
        String trimmedArn = requireNonBlank(runtimeArn, "app.ai.agentcore.runtime-arn",
                "APP_AI_AGENTCORE_RUNTIME_ARN");
        String trimmedEndpoint = requireNonBlank(endpoint, "app.ai.agentcore.endpoint",
                "APP_AI_AGENTCORE_ENDPOINT");
        Matcher matcher = RUNTIME_ARN.matcher(trimmedArn);
        if (!matcher.matches()) {
            throw new IllegalStateException(
                    "app.ai.agentcore.runtime-arn은 full AgentCore Runtime ARN이어야 합니다"
                            + "(arn:aws:bedrock-agentcore:<region>:<account>:runtime/<id>).");
        }
        String arnRegion = matcher.group(1);
        if (!arnRegion.equals(region)) {
            // client는 aws.region으로 만들어지므로 다른 리전 ARN이면 호출이 반드시 실패한다(설정 오류를 기동에서 잡는다).
            throw new IllegalStateException(
                    "app.ai.agentcore.runtime-arn의 region(" + arnRegion + ")이 aws.region(" + region
                            + ")과 다릅니다.");
        }
        if (!ENDPOINT.matcher(trimmedEndpoint).matches()) {
            throw new IllegalStateException(
                    "app.ai.agentcore.endpoint는 AgentCore endpoint 이름이어야 합니다(영숫자로 시작, "
                            + "영숫자·'-'·'_' 100자 이내).");
        }
        return new AgentCoreProperties(trimmedArn, trimmedEndpoint, region);
    }

    @Bean
    BedrockAgentCoreClient bedrockAgentCoreClient(AgentCoreProperties properties) {
        return BedrockAgentCoreClient.builder()
                .region(Region.of(properties.region()))
                .overrideConfiguration(override -> override
                        .apiCallTimeout(CALL_TIMEOUT)
                        .apiCallAttemptTimeout(ATTEMPT_TIMEOUT)
                        // 접수는 멱등이 아니다 — SDK 자동 재시도로 같은 taskId를 두 번 보내지 않는다.
                        .retryStrategy(AwsRetryStrategy.doNotRetry()))
                .build();
    }

    /** 접수 최대 대기가 PROCESSING TTL의 절반 미만인지 기동 시 강제한다(HTTP dispatcher와 같은 불변식). */
    private static void requireAckWaitWithinTaskLifetime() {
        if (CALL_TIMEOUT.compareTo(TimelineTaskService.PROCESSING_TTL.dividedBy(2)) >= 0) {
            throw new IllegalStateException(
                    "AgentCore api-call-timeout(" + CALL_TIMEOUT + ")은 PROCESSING TTL의 절반보다 짧아야 합니다.");
        }
    }

    private static String requireNonBlank(String value, String property, String envKey) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    property + "은(는) agentcore 모드에서 필수입니다(" + envKey + " 미주입).");
        }
        return value.trim();
    }
}
