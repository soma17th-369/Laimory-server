package com.laimory.server.config;

import io.lettuce.core.ClientOptions;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientOptionsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Lettuce 클라이언트 옵션(#583) — 연결이 끊긴 동안의 명령을 쌓아 두지 않고 즉시 거절한다.
 *
 * <p>Lettuce 기본값({@code DisconnectedBehavior.DEFAULT} + 자동 재연결)은 끊긴 동안 명령을 버퍼에 쌓았다가
 * command timeout({@code spring.data.redis.timeout})에 실패시킨다. 그러면 Redis 장애 동안 캐시 강등
 * ({@code FailSafeCacheErrorHandler}) 경로가 요청마다 조회·적재 두 번의 timeout을 기다린 뒤에야 DB로 가서,
 * Redis가 필요 없는 API까지 수 초씩 느려진다. 즉시 거절하면 강등이 곧바로 일어난다. 자동 재연결은 그대로다.
 *
 * <p>대가: 재연결되는 짧은 순간의 명령도 기다려 주지 않고 실패한다 — 캐시는 강등되고, draft task처럼 Redis가
 * 권위인 쓰기는 그 요청이 실패한다. Boot가 만드는 나머지 옵션(timeout·connect timeout·SSL)은 builder customizer라
 * 그대로 유지된다.
 */
@Configuration
public class RedisClientConfig {

    @Bean
    LettuceClientOptionsBuilderCustomizer rejectCommandsWhileDisconnected() {
        return builder -> builder.disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS);
    }
}
