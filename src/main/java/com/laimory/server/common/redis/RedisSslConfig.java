package com.laimory.server.common.redis;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 내부망(프라이빗 서브넷 + SG 격리) Redis 가 self-signed 인증서로 TLS 를 제공할 때,
 * 앱이 그 인증서를 신뢰하도록 Lettuce 의 peer 검증을 비활성화한다.
 *
 * <p>통신은 여전히 TLS 로 암호화되지만 인증서 체인/호스트명 검증은 생략한다. MITM 방어가 필요한
 * 공개망에는 부적합하므로 {@code app.redis.ssl-insecure=true} 일 때만 활성화한다. 실제 CA 서명
 * 인증서를 도입하면 이 값을 false(기본)로 두어 정상 검증을 유지한다.
 */
@Configuration
@ConditionalOnProperty(name = "app.redis.ssl-insecure", havingValue = "true")
public class RedisSslConfig {

    @Bean
    LettuceClientConfigurationBuilderCustomizer redisInsecureTlsCustomizer() {
        return builder -> builder.useSsl().disablePeerVerification();
    }
}
