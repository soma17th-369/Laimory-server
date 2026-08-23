package com.laimory.server.auth.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.laimory.server.auth.token.AuthTokens;
import com.laimory.server.common.redis.RedisGateway;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * AppCodeStore ↔ 실 Redis save/consume(GETDEL 일회성) 왕복 검증.
 * 실행: docker compose up -d 후 ./gradlew integrationTest
 */
@SpringBootTest
@ActiveProfiles("docker")
@Tag("integration")
class AppCodeStoreIntegrationTest {

    private static final String KEY_PREFIX = "auth:app-code:";

    @Autowired
    private AppCodeStore appCodeStore;

    @Autowired
    private RedisGateway redisGateway;

    @Test
    void save_thenConsume_returnsEntry_andSecondConsumeReturnsNull() {
        String hash = AuthTokens.sha256Hex("it-" + UUID.randomUUID());
        try {
            AppCodeStore.AppCodeEntry entry = new AppCodeStore.AppCodeEntry(1234L, "challenge-abc");
            appCodeStore.save(hash, entry, Duration.ofMinutes(1));

            AppCodeStore.AppCodeEntry first = appCodeStore.consume(hash);
            assertThat(first).isEqualTo(entry);

            // GETDEL 일회성: 같은 해시로 두 번째 소비는 null.
            assertThat(appCodeStore.consume(hash)).isNull();
        } finally {
            redisGateway.delete(KEY_PREFIX + hash); // consume이 이미 지웠어도 무해
        }
    }

    @Test
    void consume_unknownHash_returnsNull() {
        String unknownHash = AuthTokens.sha256Hex("it-" + UUID.randomUUID());

        assertThat(appCodeStore.consume(unknownHash)).isNull();
    }
}
