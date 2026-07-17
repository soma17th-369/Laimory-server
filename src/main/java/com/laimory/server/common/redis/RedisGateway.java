package com.laimory.server.common.redis;

import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 모든 Redis 접근의 단일 진입점(gateway). 논리 키 앞에 환경 prefix(예: {@code dev_})를 붙여,
 * dev/prod가 하나의 Redis 인스턴스를 공유해도 키 네임스페이스가 충돌·오염되지 않게 한다.
 *
 * <p>prefix는 {@code app.redis.key-prefix}(env {@code REDIS_KEY_PREFIX})에서 온다. prod·로컬은
 * 빈 문자열이라 논리 키를 그대로 쓴다(기존 동작 유지). 이 클래스만 {@link StringRedisTemplate}을
 * 보유하며, 다른 코드의 Redis 직접 접근은 ArchUnit(RedisAccessArchTest)으로 빌드에서 금지한다.
 *
 * <p><b>불변식:</b> 호출부는 항상 prefix 없는 <b>논리 키</b>(예: {@code timeline:draft-task:{id}})만
 * 넘긴다 — 환경 prefix 부착은 전적으로 이 클래스의 책임이다.
 */
@Component
public class RedisGateway {

    // GET-비교와 PEXPIRE/DEL을 Lua로 원자화한다 — 두 명령으로 나누면 비교와 실행 사이에 내 lease가
    // 만료되고 다른 holder가 선점한 경우 남의 lease를 갱신/삭제하는 경합이 생긴다(Redis 공식 lock 패턴).
    private static final RedisScript<Long> EXPIRE_IF_VALUE_MATCHES = new DefaultRedisScript<>("""
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('pexpire', KEYS[1], ARGV[2])
            end
            return 0
            """, Long.class);

    private static final RedisScript<Long> DELETE_IF_VALUE_MATCHES = new DefaultRedisScript<>("""
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('del', KEYS[1])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate template;
    private final String prefix;

    public RedisGateway(StringRedisTemplate template,
                         @Value("${app.redis.key-prefix:}") String prefix) {
        this.template = template;
        this.prefix = prefix;
    }

    public void set(String logicalKey, String value, Duration ttl) {
        template.opsForValue().set(prefix + logicalKey, value, ttl);
    }

    public String get(String logicalKey) {
        return template.opsForValue().get(prefix + logicalKey);
    }

    public Boolean delete(String logicalKey) {
        return template.delete(prefix + logicalKey);
    }

    /**
     * 값을 원자적으로 읽고 삭제한다(GETDEL, Redis 6.2+). 키가 없으면 null.
     * 일회용 코드 소비용 — 동시 소비 경합에서 정확히 한 호출만 값을 받는다.
     */
    public String getAndDelete(String logicalKey) {
        return template.opsForValue().getAndDelete(prefix + logicalKey);
    }

    /**
     * 키 값을 원자적으로 1 증가시키고 증가 후 값을 반환한다(키가 없으면 0에서 시작해 1을 반환).
     * 최초 생성(반환 1)일 때만 TTL을 부여한다.
     */
    public long increment(String logicalKey, Duration ttl) {
        String key = prefix + logicalKey;
        Long value = template.opsForValue().increment(key);
        if (value == null) {
            // 파이프라인/트랜잭션 맥락에서만 null — 이 gateway는 그 맥락을 지원하지 않으므로 불변식 위반.
            throw new IllegalStateException("Redis increment가 null을 반환했습니다: " + logicalKey);
        }
        if (value == 1) {
            // 반환값은 의도적으로 무시: 실패해도 카운터가 오래 남는 가용성 문제일 뿐, 보안(replay 허용) 문제가 아니다.
            template.expire(key, ttl);
        }
        return value;
    }

    /**
     * 키가 없을 때만 값을 원자적으로 저장한다(SET NX + TTL). 저장했으면 true, 이미 있으면 false.
     * 분산 lease 선점용 — 동시 선점 경합에서 정확히 한 호출만 true를 받는다.
     */
    public boolean setIfAbsent(String logicalKey, String value, Duration ttl) {
        Boolean acquired = template.opsForValue().setIfAbsent(prefix + logicalKey, value, ttl);
        if (acquired == null) {
            // 파이프라인/트랜잭션 맥락에서만 null — 이 gateway는 그 맥락을 지원하지 않으므로 불변식 위반.
            throw new IllegalStateException("Redis setIfAbsent가 null을 반환했습니다: " + logicalKey);
        }
        return acquired;
    }

    /** 저장된 값이 기대값과 일치할 때만 TTL을 갱신한다(Lua 원자). 갱신했으면 true. */
    public boolean expireIfValueMatches(String logicalKey, String expectedValue, Duration ttl) {
        Long result = template.execute(EXPIRE_IF_VALUE_MATCHES, List.of(prefix + logicalKey),
                expectedValue, String.valueOf(ttl.toMillis()));
        return requireScriptResult(result, logicalKey) == 1L;
    }

    /** 저장된 값이 기대값과 일치할 때만 삭제한다(Lua 원자, compare-and-delete). 삭제했으면 true. */
    public boolean deleteIfValueMatches(String logicalKey, String expectedValue) {
        Long result = template.execute(DELETE_IF_VALUE_MATCHES, List.of(prefix + logicalKey), expectedValue);
        return requireScriptResult(result, logicalKey) == 1L;
    }

    private static long requireScriptResult(Long result, String logicalKey) {
        if (result == null) {
            // 파이프라인/트랜잭션 맥락에서만 null — 이 gateway는 그 맥락을 지원하지 않으므로 불변식 위반.
            throw new IllegalStateException("Redis script가 null을 반환했습니다: " + logicalKey);
        }
        return result;
    }
}
