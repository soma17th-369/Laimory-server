package com.laimory.server.common.redis;

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
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;

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

    private static final String LOGICAL_KEY = "timeline:draft-task:abc";

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
    void setAndAddToSortedSets_prefixesAllThreeKeys_andPassesAtomicScriptArguments() {
        // 책임 경계 고정(T13): 값 key + 두 index key가 전부 prefix된 채 script 한 번의 execute로 전달되고,
        // TTL은 한 인자로 값 PSETEX와 만료 대상 index PEXPIRE에 함께 쓰인다(script 내부 계약).
        RedisGateway redis = new RedisGateway(template, "dev_");
        when(template.execute(ArgumentMatchers.<RedisScript<Long>>any(),
                eq(List.of("dev_timeline:draft-task:abc",
                        "dev_timeline:draft-task:processing-index",
                        "dev_timeline:draft-task:user:7:processing")),
                eq("180000"), eq("{\"status\":\"PROCESSING\"}"),
                eq("1780000000000"), eq("abc"))).thenReturn(1L);

        redis.setAndAddToSortedSets(LOGICAL_KEY, "{\"status\":\"PROCESSING\"}",
                Duration.ofMinutes(3), "timeline:draft-task:processing-index",
                "timeline:draft-task:user:7:processing", "abc", 1_780_000_000_000L);
    }

    @Test
    void setAndRemoveFromSortedSets_prefixesAllThreeKeys_andPassesAtomicScriptArguments() {
        RedisGateway redis = new RedisGateway(template, "dev_");
        when(template.execute(ArgumentMatchers.<RedisScript<Long>>any(),
                eq(List.of("dev_timeline:draft-task:abc",
                        "dev_timeline:draft-task:processing-index",
                        "dev_timeline:draft-task:user:7:processing")),
                eq("86400000"), eq("{\"status\":\"SUCCESS\"}"), eq("abc")))
                .thenReturn(1L);

        redis.setAndRemoveFromSortedSets(LOGICAL_KEY, "{\"status\":\"SUCCESS\"}",
                Duration.ofHours(24), "timeline:draft-task:processing-index",
                "timeline:draft-task:user:7:processing", "abc");
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
    void pruneAndCountSortedSet_prefixesKey_andReturnsCount() {
        RedisGateway redis = new RedisGateway(template, "dev_");
        when(template.execute(ArgumentMatchers.<RedisScript<Long>>any(),
                eq(List.of("dev_timeline:draft-task:processing-index")),
                eq("1779996400000"), eq("1779999400000"))).thenReturn(3L);

        assertThat(redis.pruneAndCountSortedSet("timeline:draft-task:processing-index",
                1_779_996_400_000L, 1_779_999_400_000L)).isEqualTo(3L);
    }

    @Test
    void atomicSortedSetOps_nullFromTemplate_throwIllegalState() {
        RedisGateway redis = new RedisGateway(template, "");
        when(template.execute(ArgumentMatchers.<RedisScript<Long>>any(), any(), any(Object[].class)))
                .thenReturn(null);

        assertThatThrownBy(() -> redis.setAndAddToSortedSets(LOGICAL_KEY, "v",
                Duration.ofMinutes(1), "index", "user-index", "abc", 1L))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> redis.setAndRemoveFromSortedSets(LOGICAL_KEY, "v",
                Duration.ofMinutes(1), "index", "user-index", "abc"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> redis.pruneAndCountSortedSet("index", 1L, 2L))
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
