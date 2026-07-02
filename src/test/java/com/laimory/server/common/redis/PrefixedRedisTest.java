package com.laimory.server.common.redis;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

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
}
