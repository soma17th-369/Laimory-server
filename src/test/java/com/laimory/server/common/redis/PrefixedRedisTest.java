package com.laimory.server.common.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * PrefixedRedis가 논리 키 앞에 환경 prefix를 올바르게 부착하는지 검증한다(prefix 동작의 단일 검증 지점).
 */
@ExtendWith(MockitoExtension.class)
class PrefixedRedisTest {

    @Mock
    private StringRedisTemplate template;
    @Mock
    private ValueOperations<String, String> valueOps;

    private static final String LOGICAL_KEY = "timeline:draft-task:abc";

    @Test
    void emptyPrefix_usesLogicalKeyAsIs() {
        when(template.opsForValue()).thenReturn(valueOps);
        PrefixedRedis redis = new PrefixedRedis(template, "");

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
        PrefixedRedis redis = new PrefixedRedis(template, "dev_");

        redis.set(LOGICAL_KEY, "v", Duration.ofMinutes(1));
        redis.get(LOGICAL_KEY);
        redis.delete(LOGICAL_KEY);

        verify(valueOps).set("dev_timeline:draft-task:abc", "v", Duration.ofMinutes(1));
        verify(valueOps).get("dev_timeline:draft-task:abc");
        verify(template).delete("dev_timeline:draft-task:abc");
    }

    @Test
    void increment_prependsPrefix_andSetsTtlOnlyOnFirstIncrement() {
        when(template.opsForValue()).thenReturn(valueOps);
        PrefixedRedis redis = new PrefixedRedis(template, "dev_");
        when(valueOps.increment("dev_" + LOGICAL_KEY)).thenReturn(1L);

        long first = redis.increment(LOGICAL_KEY, Duration.ofHours(1));

        assertThat(first).isEqualTo(1L);
        verify(template).expire("dev_" + LOGICAL_KEY, Duration.ofHours(1));
    }

    @Test
    void increment_secondIncrement_doesNotResetTtl() {
        when(template.opsForValue()).thenReturn(valueOps);
        PrefixedRedis redis = new PrefixedRedis(template, "");
        when(valueOps.increment(LOGICAL_KEY)).thenReturn(2L);

        long second = redis.increment(LOGICAL_KEY, Duration.ofHours(1));

        assertThat(second).isEqualTo(2L);
        verify(template, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    void increment_nullFromTemplate_throwsIllegalState() {
        // 파이프라인/트랜잭션 맥락에서만 null — facade는 그 맥락을 지원하지 않으므로 불변식 위반으로 차단.
        when(template.opsForValue()).thenReturn(valueOps);
        PrefixedRedis redis = new PrefixedRedis(template, "");
        when(valueOps.increment(LOGICAL_KEY)).thenReturn(null);

        assertThatThrownBy(() -> redis.increment(LOGICAL_KEY, Duration.ofHours(1)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void setIfAbsent_prependsPrefix_andReturnsAcquisition() {
        when(template.opsForValue()).thenReturn(valueOps);
        PrefixedRedis redis = new PrefixedRedis(template, "dev_");
        when(valueOps.setIfAbsent("dev_" + LOGICAL_KEY, "holder", Duration.ofHours(1))).thenReturn(true);

        assertThat(redis.setIfAbsent(LOGICAL_KEY, "holder", Duration.ofHours(1))).isTrue();
    }

    @Test
    void setIfAbsent_existingKey_returnsFalse() {
        when(template.opsForValue()).thenReturn(valueOps);
        PrefixedRedis redis = new PrefixedRedis(template, "");
        when(valueOps.setIfAbsent(LOGICAL_KEY, "holder", Duration.ofHours(1))).thenReturn(false);

        assertThat(redis.setIfAbsent(LOGICAL_KEY, "holder", Duration.ofHours(1))).isFalse();
    }

    @Test
    void setIfAbsent_nullFromTemplate_throwsIllegalState() {
        when(template.opsForValue()).thenReturn(valueOps);
        PrefixedRedis redis = new PrefixedRedis(template, "");
        when(valueOps.setIfAbsent(LOGICAL_KEY, "holder", Duration.ofHours(1))).thenReturn(null);

        assertThatThrownBy(() -> redis.setIfAbsent(LOGICAL_KEY, "holder", Duration.ofHours(1)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void expireIfValueMatches_executesScriptWithPrefixedKey_andMapsResult() {
        // Lua 인자 계약: KEYS[1]=prefix 부착 키, ARGV[1]=기대값, ARGV[2]=밀리초 TTL 문자열.
        PrefixedRedis redis = new PrefixedRedis(template, "dev_");
        when(template.execute(ArgumentMatchers.<RedisScript<Long>>any(),
                eq(List.of("dev_" + LOGICAL_KEY)), eq("holder"), eq("3600000"))).thenReturn(1L);

        assertThat(redis.expireIfValueMatches(LOGICAL_KEY, "holder", Duration.ofHours(1))).isTrue();
    }

    @Test
    void expireIfValueMatches_holderMismatch_returnsFalse() {
        PrefixedRedis redis = new PrefixedRedis(template, "");
        when(template.execute(ArgumentMatchers.<RedisScript<Long>>any(),
                eq(List.of(LOGICAL_KEY)), eq("holder"), eq("3600000"))).thenReturn(0L);

        assertThat(redis.expireIfValueMatches(LOGICAL_KEY, "holder", Duration.ofHours(1))).isFalse();
    }

    @Test
    void deleteIfValueMatches_executesScriptWithPrefixedKey_andMapsResult() {
        PrefixedRedis redis = new PrefixedRedis(template, "dev_");
        when(template.execute(ArgumentMatchers.<RedisScript<Long>>any(),
                eq(List.of("dev_" + LOGICAL_KEY)), eq("holder"))).thenReturn(1L);

        assertThat(redis.deleteIfValueMatches(LOGICAL_KEY, "holder")).isTrue();
    }

    @Test
    void deleteIfValueMatches_holderMismatch_returnsFalse() {
        PrefixedRedis redis = new PrefixedRedis(template, "");
        when(template.execute(ArgumentMatchers.<RedisScript<Long>>any(),
                eq(List.of(LOGICAL_KEY)), eq("holder"))).thenReturn(0L);

        assertThat(redis.deleteIfValueMatches(LOGICAL_KEY, "holder")).isFalse();
    }

    @Test
    void scriptOps_nullFromTemplate_throwIllegalState() {
        PrefixedRedis redis = new PrefixedRedis(template, "");
        when(template.execute(ArgumentMatchers.<RedisScript<Long>>any(), any(), any(Object[].class)))
                .thenReturn(null);

        assertThatThrownBy(() -> redis.deleteIfValueMatches(LOGICAL_KEY, "holder"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> redis.expireIfValueMatches(LOGICAL_KEY, "holder", Duration.ofHours(1)))
                .isInstanceOf(IllegalStateException.class);
    }
}
