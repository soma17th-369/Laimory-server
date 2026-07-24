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

    // task JSON과 PROCESSING 관측 인덱스를 한 원자 경계에서 갱신한다. 둘을 별도 명령으로 쓰면
    // 앱/Redis 장애 사이에 task와 인덱스가 어긋나 stuck gauge가 거짓말할 수 있다.
    private static final RedisScript<Long> SET_AND_SORTED_SET_ADD = new DefaultRedisScript<>("""
            redis.call('psetex', KEYS[1], ARGV[1], ARGV[2])
            return redis.call('zadd', KEYS[2], ARGV[3], ARGV[4])
            """, Long.class);

    private static final RedisScript<Long> SET_AND_SORTED_SET_REMOVE = new DefaultRedisScript<>("""
            redis.call('psetex', KEYS[1], ARGV[1], ARGV[2])
            return redis.call('zrem', KEYS[2], ARGV[3])
            """, Long.class);

    // task TTL보다 오래된 고아 member를 먼저 제거한 뒤, 아직 유효하지만 stuck threshold를 넘긴
    // member 수를 한 번의 Redis 왕복으로 센다.
    private static final RedisScript<Long> PRUNE_AND_COUNT_SORTED_SET = new DefaultRedisScript<>("""
            redis.call('zremrangebyscore', KEYS[1], '-inf', ARGV[1])
            return redis.call('zcount', KEYS[1], '(' .. ARGV[1], ARGV[2])
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

    /**
     * TTL 값 저장과 sorted-set member 추가를 원자적으로 수행한다.
     *
     * <p>두 키 모두 prefix 없는 논리 키이며, score는 epoch milliseconds처럼 호출부가 정한 단위를 쓴다.
     */
    public void setAndAddToSortedSet(String logicalValueKey, String value, Duration ttl,
                                     String logicalSortedSetKey, String member, long score) {
        Long result = template.execute(SET_AND_SORTED_SET_ADD,
                List.of(prefix + logicalValueKey, prefix + logicalSortedSetKey),
                String.valueOf(ttl.toMillis()), value, String.valueOf(score), member);
        requireScriptResult(result, logicalValueKey);
    }

    /** TTL 값 저장과 sorted-set member 제거를 원자적으로 수행한다. */
    public void setAndRemoveFromSortedSet(String logicalValueKey, String value, Duration ttl,
                                          String logicalSortedSetKey, String member) {
        Long result = template.execute(SET_AND_SORTED_SET_REMOVE,
                List.of(prefix + logicalValueKey, prefix + logicalSortedSetKey),
                String.valueOf(ttl.toMillis()), value, member);
        requireScriptResult(result, logicalValueKey);
    }

    /**
     * {@code score <= expiredScore} member를 제거하고
     * {@code expiredScore < score <= inclusiveUpperScore} member 수를 반환한다.
     */
    public long pruneAndCountSortedSet(String logicalSortedSetKey, long expiredScore,
                                       long inclusiveUpperScore) {
        Long result = template.execute(PRUNE_AND_COUNT_SORTED_SET,
                List.of(prefix + logicalSortedSetKey),
                String.valueOf(expiredScore), String.valueOf(inclusiveUpperScore));
        return requireScriptResult(result, logicalSortedSetKey);
    }

    private static long requireScriptResult(Long result, String logicalKey) {
        if (result == null) {
            // 파이프라인/트랜잭션 맥락에서만 null — 이 gateway는 그 맥락을 지원하지 않으므로 불변식 위반.
            throw new IllegalStateException("Redis script가 null을 반환했습니다: " + logicalKey);
        }
        return result;
    }
}
