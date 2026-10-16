package com.laimory.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.lettuce.core.ClientOptions;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

/** Boot Redis 자동 구성 위에 #583 customizer가 적용되고 Boot 기본 옵션이 유지되는지 배선을 검증한다(연결 없음). */
class RedisClientConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RedisAutoConfiguration.class))
            .withUserConfiguration(RedisClientConfig.class)
            .withPropertyValues("spring.data.redis.host=localhost",
                    "spring.data.redis.timeout=2s",
                    "spring.data.redis.connect-timeout=2s");

    @Test
    void rejectsCommandsWhileDisconnectedAndKeepsBootTimeouts() {
        contextRunner.run(context -> {
            ClientOptions options = context.getBean(LettuceConnectionFactory.class)
                    .getClientConfiguration().getClientOptions().orElseThrow();

            assertThat(options.getDisconnectedBehavior())
                    .isEqualTo(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS);
            assertThat(options.isAutoReconnect()).isTrue();
            assertThat(options.getSocketOptions().getConnectTimeout()).isEqualTo(Duration.ofSeconds(2));
            assertThat(options.getTimeoutOptions().isTimeoutCommands()).isTrue();
        });
    }
}
