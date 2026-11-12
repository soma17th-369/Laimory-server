package com.laimory.server.common.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.test.context.ActiveProfiles;

/**
 * dual write(#638) 반영 규칙을 실 Redis 상태로 검증한다. primary는 앱 연결(db 0), secondary는 같은 로컬 Redis의 db 1이다.
 * 원자 연산은 primary 결과가 정하고 secondary에는 그 결과만 반영되는지, TTL이 함께 옮겨지는지를 본다.
 */
@SpringBootTest
@ActiveProfiles("docker")
@Tag("integration")
class RedisMirrorIntegrationTest {

    private static final Duration TTL = Duration.ofMinutes(10);

    @Autowired
    private StringRedisTemplate primary;

    @Value("${spring.data.redis.host}")
    private String redisHost;

    @Value("${spring.data.redis.port}")
    private int redisPort;

    private LettuceConnectionFactory secondaryConnection;
    private StringRedisTemplate secondary;
    private RedisGateway gateway;
    private String prefix;

    @BeforeEach
    void setUp() {
        RedisStandaloneConfiguration db1 = new RedisStandaloneConfiguration(redisHost, redisPort);
        db1.setDatabase(1);
        secondaryConnection = new LettuceConnectionFactory(db1);
        secondaryConnection.afterPropertiesSet();
        secondaryConnection.start();
        secondary = new StringRedisTemplate(secondaryConnection);
        prefix = "mirror-it-" + UUID.randomUUID() + ":";
        gateway = new RedisGateway(primary, new RedisMirror(secondary), prefix);
    }

    @AfterEach
    void tearDown() {
        Set<String> primaryKeys = primary.keys(prefix + "*");
        if (primaryKeys != null && !primaryKeys.isEmpty()) {
            primary.delete(primaryKeys);
        }
        Set<String> secondaryKeys = secondary.keys(prefix + "*");
        if (secondaryKeys != null && !secondaryKeys.isEmpty()) {
            secondary.delete(secondaryKeys);
        }
        secondaryConnection.destroy();
    }

    @Test
    void setCopiesValueAndTtlToSecondary() {
        gateway.set("k", "v", TTL);

        assertThat(secondary.opsForValue().get(prefix + "k")).isEqualTo("v");
        assertThat(secondaryTtlMillis("k")).isPositive().isLessThanOrEqualTo(TTL.toMillis());
    }

    @Test
    void setIfAbsentWinnerCopiesValueAndTtlToSecondary() {
        assertThat(gateway.setIfAbsent("lock", "owner", TTL)).isTrue();

        assertThat(secondary.opsForValue().get(prefix + "lock")).isEqualTo("owner");
        assertThat(secondaryTtlMillis("lock")).isPositive();
    }

    @Test
    void setIfAbsentLoserLeavesSecondaryUntouched() {
        primary.opsForValue().set(prefix + "lock", "first-owner", TTL);

        assertThat(gateway.setIfAbsent("lock", "second-owner", TTL)).isFalse();

        assertThat(secondary.hasKey(prefix + "lock")).isFalse();
    }

    @Test
    void getAndDeleteRemovesSecondaryKeyWhenPrimaryReturnsValue() {
        gateway.set("code", "payload", TTL);

        assertThat(gateway.getAndDelete("code")).isEqualTo("payload");

        assertThat(secondary.hasKey(prefix + "code")).isFalse();
    }

    @Test
    void deleteRemovesSecondaryKey() {
        gateway.set("k", "v", TTL);

        gateway.delete("k");

        assertThat(secondary.hasKey(prefix + "k")).isFalse();
    }

    @Test
    void setIfPresentKeepingTtlReplacesSecondaryValueAndKeepsItsTtl() {
        gateway.set("task", "v1", TTL);

        assertThat(gateway.setIfPresentKeepingTtl("task", "v2")).isTrue();

        assertThat(secondary.opsForValue().get(prefix + "task")).isEqualTo("v2");
        assertThat(secondaryTtlMillis("task")).isPositive().isLessThanOrEqualTo(TTL.toMillis());
    }

    @Test
    void setIfPresentReplacesSecondaryValueWithNewTtl() {
        gateway.set("task", "processing", TTL);

        assertThat(gateway.setIfPresent("task", "terminal", Duration.ofHours(24))).isTrue();

        assertThat(secondary.opsForValue().get(prefix + "task")).isEqualTo("terminal");
        assertThat(secondaryTtlMillis("task")).isGreaterThan(TTL.toMillis());
    }

    @Test
    void setIfPresentOnMissingPrimaryKeyLeavesSecondaryUntouched() {
        secondary.opsForValue().set(prefix + "task", "secondary-only", TTL);

        assertThat(gateway.setIfPresent("task", "terminal", Duration.ofHours(24))).isFalse();

        assertThat(secondary.opsForValue().get(prefix + "task")).isEqualTo("secondary-only");
    }

    @Test
    void expireCopiesNewTtlToSecondary() {
        gateway.set("index", "v", Duration.ofMinutes(1));

        assertThat(gateway.expire("index", Duration.ofHours(1))).isTrue();

        assertThat(secondaryTtlMillis("index")).isGreaterThan(Duration.ofMinutes(1).toMillis());
    }

    @Test
    void sortedSetWritesLeaveSecondaryEqualToPrimary() {
        gateway.addToSortedSet("queue", "a", 100L);
        gateway.addToSortedSetIfAbsent("queue", "b", 200L);
        gateway.addToSortedSetIfAbsent("queue", "a", 999L);
        gateway.addToSortedSet("queue", "c", 300L);
        gateway.removeFromSortedSet("queue", List.of("c"));
        gateway.addToSortedSet("queue", "old", 10L);
        gateway.pruneSortedSetByScore("queue", 50L);

        assertThat(scoresOf(secondary, "queue")).isEqualTo(scoresOf(primary, "queue"))
                .containsExactly("a=100.0", "b=200.0");
    }

    private long secondaryTtlMillis(String logicalKey) {
        Long ttl = secondary.getExpire(prefix + logicalKey, java.util.concurrent.TimeUnit.MILLISECONDS);
        return ttl == null ? -2 : ttl;
    }

    private List<String> scoresOf(StringRedisTemplate redis, String logicalKey) {
        Set<TypedTuple<String>> tuples = redis.opsForZSet().rangeWithScores(prefix + logicalKey, 0, -1);
        return tuples == null ? List.of() : tuples.stream()
                .map(tuple -> tuple.getValue() + "=" + tuple.getScore())
                .toList();
    }
}
