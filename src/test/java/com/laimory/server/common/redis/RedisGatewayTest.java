package com.laimory.server.common.redis;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.data.redis.connection.RedisStringCommands.SetOption;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.data.redis.serializer.RedisSerializer;

/**
 * RedisGateway가 논리 키 앞에 환경 prefix를 올바르게 부착하는지 검증한다(prefix 동작의 단일 검증 지점).
 */
@ExtendWith(MockitoExtension.class)
class RedisGatewayTest {

    @Mock
    private StringRedisTemplate template;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private ZSetOperations<String, String> zSetOps;
    @Mock
    private RedisConnection connection;
    @Mock
    private RedisStringCommands stringCommands;

    private static final String LOGICAL_KEY = "timeline:draft-task:abc";

    /** {@code template.execute(RedisCallback)}이 mock connection으로 callback을 실제 실행하게 한다. */
    private void runCallbacksWithMockConnection() {
        when(template.getStringSerializer()).thenReturn(RedisSerializer.string());
        when(connection.stringCommands()).thenReturn(stringCommands);
        when(template.execute(ArgumentMatchers.<RedisCallback<Boolean>>any()))
                .thenAnswer(invocation -> invocation.<RedisCallback<Boolean>>getArgument(0).doInRedis(connection));
    }

    @Test
    void emptyPrefix_usesLogicalKeyAsIs() {
        when(template.opsForValue()).thenReturn(valueOps);
        RedisGateway redis = new RedisGateway(template, "");

        redis.set(LOGICAL_KEY, "v", Duration.ofMinutes(1));
        redis.get(LOGICAL_KEY);
        redis.delete(LOGICAL_KEY);

        verify(valueOps).set("timeline:draft-task:abc", "v", Duration.ofMinutes(1));
        verify(valueOps).get("timeline:draft-task:abc");
        verify(template).delete("timeline:draft-task:abc");
    }

    @Test
    void nonEmptyPrefix_prependsToEveryKey() {
        when(template.opsForValue()).thenReturn(valueOps);
        RedisGateway redis = new RedisGateway(template, "dev_");

        redis.set(LOGICAL_KEY, "v", Duration.ofMinutes(1));
        redis.get(LOGICAL_KEY);
        redis.delete(LOGICAL_KEY);

        verify(valueOps).set("dev_timeline:draft-task:abc", "v", Duration.ofMinutes(1));
        verify(valueOps).get("dev_timeline:draft-task:abc");
        verify(template).delete("dev_timeline:draft-task:abc");
    }

    @Test
    void setIfPresentKeepingTtl_prefixesKey_andSendsXxKeepTtl() {
        runCallbacksWithMockConnection();
        RedisGateway redis = new RedisGateway(template, "dev_");
        when(stringCommands.set(any(byte[].class), any(byte[].class), any(Expiration.class), eq(SetOption.ifPresent())))
                .thenReturn(true);

        assertThat(redis.setIfPresentKeepingTtl(LOGICAL_KEY, "new-json")).isTrue();

        ArgumentCaptor<byte[]> keyCaptor = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<byte[]> valueCaptor = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<Expiration> expirationCaptor = ArgumentCaptor.forClass(Expiration.class);
        verify(stringCommands).set(keyCaptor.capture(), valueCaptor.capture(),
                expirationCaptor.capture(), eq(SetOption.ifPresent()));
        assertThat(new String(keyCaptor.getValue(), UTF_8)).isEqualTo("dev_timeline:draft-task:abc");
        assertThat(new String(valueCaptor.getValue(), UTF_8)).isEqualTo("new-json");
        assertThat(expirationCaptor.getValue().isKeepTtl()).isTrue();
    }

    @Test
    void setIfPresent_prefixesKey_andSendsXxWithPxTtl_missingKeyReturnsFalse() {
        runCallbacksWithMockConnection();
        RedisGateway redis = new RedisGateway(template, "dev_");
        when(stringCommands.set(any(byte[].class), any(byte[].class), any(Expiration.class), eq(SetOption.ifPresent())))
                .thenReturn(false);

        assertThat(redis.setIfPresent(LOGICAL_KEY, "terminal-json", Duration.ofHours(24))).isFalse();

        ArgumentCaptor<byte[]> keyCaptor = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<Expiration> expirationCaptor = ArgumentCaptor.forClass(Expiration.class);
        verify(stringCommands).set(keyCaptor.capture(), any(byte[].class),
                expirationCaptor.capture(), eq(SetOption.ifPresent()));
        assertThat(new String(keyCaptor.getValue(), UTF_8)).isEqualTo("dev_timeline:draft-task:abc");
        assertThat(expirationCaptor.getValue().isKeepTtl()).isFalse();
        assertThat(expirationCaptor.getValue().getExpirationTimeInMilliseconds()).isEqualTo(86_400_000L);
    }

    @Test
    void taskIndexOps_prefixKeys_andUseNativeCommands() {
        when(template.opsForZSet()).thenReturn(zSetOps);
        RedisGateway redis = new RedisGateway(template, "dev_");
        when(zSetOps.add("dev_processing-index", "abc", 1_780_000_000_000d)).thenReturn(false);
        when(template.expire("dev_user-index", Duration.ofMinutes(3))).thenReturn(true);

        redis.addToSortedSet("processing-index", "abc", 1_780_000_000_000L);

        assertThat(redis.expire("user-index", Duration.ofMinutes(3))).isTrue();
        verify(zSetOps).add("dev_processing-index", "abc", 1_780_000_000_000d);
        verify(template).expire("dev_user-index", Duration.ofMinutes(3));
    }

    @Test
    void taskIndexOps_missingOrNullResults_areExplicit() {
        when(template.opsForZSet()).thenReturn(zSetOps);
        RedisGateway redis = new RedisGateway(template, "");
        when(zSetOps.add("index", "abc", 1d)).thenReturn(null);
        when(template.expire("index", Duration.ofMinutes(3))).thenReturn(false);

        assertThatThrownBy(() -> redis.addToSortedSet("index", "abc", 1L))
                .isInstanceOf(IllegalStateException.class);
        assertThat(redis.expire("index", Duration.ofMinutes(3))).isFalse();
    }

    @Test
    void getSortedSetReverseRange_prefixesKey_andPreservesReverseOrder() {
        when(template.opsForZSet()).thenReturn(zSetOps);
        RedisGateway redis = new RedisGateway(template, "dev_");
        when(zSetOps.reverseRange("dev_timeline:draft-task:user:7:processing", 0, -1))
                .thenReturn(new LinkedHashSet<>(List.of("newest", "older", "oldest")));

        assertThat(redis.getSortedSetReverseRange("timeline:draft-task:user:7:processing"))
                .containsExactly("newest", "older", "oldest");
    }

    @Test
    void getSortedSetReverseRange_missingKey_returnsEmptyList() {
        when(template.opsForZSet()).thenReturn(zSetOps);
        RedisGateway redis = new RedisGateway(template, "");
        when(zSetOps.reverseRange("timeline:draft-task:user:7:processing", 0, -1))
                .thenReturn(new LinkedHashSet<>());

        assertThat(redis.getSortedSetReverseRange("timeline:draft-task:user:7:processing")).isEmpty();
    }

    @Test
    void multiGet_prefixesEveryKey_andAlignsValuesWithMissingAsNull() {
        when(template.opsForValue()).thenReturn(valueOps);
        RedisGateway redis = new RedisGateway(template, "dev_");
        when(valueOps.multiGet(List.of("dev_timeline:draft-task:a", "dev_timeline:draft-task:b")))
                .thenReturn(java.util.Arrays.asList("json-a", null));

        assertThat(redis.multiGet(List.of("timeline:draft-task:a", "timeline:draft-task:b")))
                .containsExactly("json-a", null);
    }

    @Test
    void removeFromSortedSet_prefixesKey_andRemovesAllMembersInOneCall() {
        when(template.opsForZSet()).thenReturn(zSetOps);
        RedisGateway redis = new RedisGateway(template, "dev_");
        when(zSetOps.remove("dev_timeline:draft-task:user:7:processing", "a", "b")).thenReturn(2L);

        assertThat(redis.removeFromSortedSet(
                "timeline:draft-task:user:7:processing", List.of("a", "b"))).isEqualTo(2L);
    }

    @Test
    void stuckIndexOps_prefixKey_andUseNativeCommands() {
        when(template.opsForZSet()).thenReturn(zSetOps);
        RedisGateway redis = new RedisGateway(template, "dev_");
        when(zSetOps.removeRangeByScore("dev_timeline:draft-task:processing-index",
                Double.NEGATIVE_INFINITY, 1_779_996_400_000d)).thenReturn(2L);
        when(zSetOps.count("dev_timeline:draft-task:processing-index",
                Double.NEGATIVE_INFINITY, 1_779_999_400_000d)).thenReturn(3L);

        assertThat(redis.pruneSortedSetByScore(
                "timeline:draft-task:processing-index", 1_779_996_400_000L)).isEqualTo(2L);
        assertThat(redis.countSortedSetByScore(
                "timeline:draft-task:processing-index", 1_779_999_400_000L)).isEqualTo(3L);
    }

    @Test
    void pendingQueueOps_prefixKey_andUseNxAndScoreBounds() {
        // 미반영 큐는 Lua 없이 평범한 명령만 쓴다 — 동시성 보장이 전부 단일 명령에서 나오기 때문이다.
        when(template.opsForZSet()).thenReturn(zSetOps);
        RedisGateway redis = new RedisGateway(template, "dev_");
        when(zSetOps.removeRangeByScore("dev_pending", Double.NEGATIVE_INFINITY, 100d)).thenReturn(2L);
        when(zSetOps.rangeByScore("dev_pending", Double.NEGATIVE_INFINITY, 500d, 0, 3))
                .thenReturn(new LinkedHashSet<>(List.of("7:42", "7:43")));
        when(zSetOps.count("dev_pending", Double.NEGATIVE_INFINITY, 500d)).thenReturn(9L);

        assertThat(redis.pruneSortedSetByScore("pending", 100L)).isEqualTo(2L);
        redis.addToSortedSetIfAbsent("pending", "7:44", 400L);
        redis.expire("pending", Duration.ofDays(30));

        // 목록과 개수가 같은 상한(500)을 써야 "개수 > 읽어온 수"가 곧 "limit에 잘렸다"를 뜻한다.
        // score의 도메인 의미(대기 시작 시각)는 store가 정한다 — gateway는 Redis 용어 그대로 둔다.
        assertThat(redis.getSortedSetRangeByScore("pending", 500L, 3)).containsExactly("7:42", "7:43");
        assertThat(redis.countSortedSetByScore("pending", 500L)).isEqualTo(9L);

        // 이미 있는 member의 score를 덮으면 최초 진입 기준 시한이 무한 연장된다.
        verify(zSetOps).addIfAbsent("dev_pending", "7:44", 400d);
        verify(template).expire("dev_pending", Duration.ofDays(30));
    }

    @Test
    void pendingQueueOps_nullFromTemplate_throwIllegalState() {
        RedisGateway redis = new RedisGateway(template, "");
        when(template.opsForZSet()).thenReturn(zSetOps);
        when(zSetOps.removeRangeByScore("pending", Double.NEGATIVE_INFINITY, 1d)).thenReturn(null);
        when(zSetOps.rangeByScore("pending", Double.NEGATIVE_INFINITY, 2d, 0, 5)).thenReturn(null);
        when(zSetOps.count("pending", Double.NEGATIVE_INFINITY, 2d)).thenReturn(null);

        assertThatThrownBy(() -> redis.pruneSortedSetByScore("pending", 1L))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> redis.getSortedSetRangeByScore("pending", 2L, 5))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> redis.countSortedSetByScore("pending", 2L))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void setIfPresent_nullFromTemplate_throwsIllegalState() {
        when(template.getStringSerializer()).thenReturn(RedisSerializer.string());
        when(template.execute(ArgumentMatchers.<RedisCallback<Boolean>>any())).thenReturn(null);
        RedisGateway redis = new RedisGateway(template, "");

        assertThatThrownBy(() -> redis.setIfPresentKeepingTtl(LOGICAL_KEY, "new"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> redis.setIfPresent(LOGICAL_KEY, "new", Duration.ofMinutes(1)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void batchReadAndRemoveOps_nullFromTemplate_throwIllegalState() {
        RedisGateway redis = new RedisGateway(template, "");
        when(template.opsForZSet()).thenReturn(zSetOps);
        when(template.opsForValue()).thenReturn(valueOps);
        when(zSetOps.reverseRange("index", 0, -1)).thenReturn(null);
        when(valueOps.multiGet(List.of("k"))).thenReturn(null);
        when(zSetOps.remove("index", "a")).thenReturn(null);

        assertThatThrownBy(() -> redis.getSortedSetReverseRange("index"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> redis.multiGet(List.of("k")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> redis.removeFromSortedSet("index", List.of("a")))
                .isInstanceOf(IllegalStateException.class);
    }
}
