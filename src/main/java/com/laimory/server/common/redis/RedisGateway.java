package com.laimory.server.common.redis;

import java.time.Duration;
import java.util.List;
import java.util.Set;
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

    // task JSON과 두 인덱스(전역 관측 + 사용자별 조회)를 한 원자 경계에서 갱신한다. 셋을 별도 명령으로
    // 쓰면 앱/Redis 장애 사이에 task와 인덱스가 어긋나 stuck gauge가 거짓말하거나 목록 후보가 유실된다.
    // 두 번째 sorted set(KEYS[3])은 값과 같은 TTL로 매번 PEXPIRE한다 — 마지막 추가 뒤 TTL 동안
    // 비활성인 key만 자연 소멸한다(member별 TTL 아님). Lua 안에서 값 JSON은 decode하지 않는다.
    private static final RedisScript<Long> SET_AND_SORTED_SETS_ADD = new DefaultRedisScript<>("""
            redis.call('psetex', KEYS[1], ARGV[1], ARGV[2])
            redis.call('zadd', KEYS[2], ARGV[3], ARGV[4])
            redis.call('zadd', KEYS[3], ARGV[3], ARGV[4])
            return redis.call('pexpire', KEYS[3], ARGV[1])
            """, Long.class);

    private static final RedisScript<Long> SET_AND_SORTED_SETS_REMOVE = new DefaultRedisScript<>("""
            redis.call('psetex', KEYS[1], ARGV[1], ARGV[2])
            redis.call('zrem', KEYS[2], ARGV[3])
            return redis.call('zrem', KEYS[3], ARGV[3])
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
     * callback token 같은 일회성 admission marker에서 동시 호출 중 정확히 하나만 true를 받게 한다.
     */
    public boolean setIfAbsent(String logicalKey, String value, Duration ttl) {
        Boolean acquired = template.opsForValue().setIfAbsent(prefix + logicalKey, value, ttl);
        if (acquired == null) {
            // 파이프라인/트랜잭션 맥락에서만 null — 이 gateway는 그 맥락을 지원하지 않으므로 불변식 위반.
            throw new IllegalStateException("Redis setIfAbsent가 null을 반환했습니다: " + logicalKey);
        }
        return acquired;
    }

    /**
     * TTL 값 저장과 두 sorted set의 같은 member/score 추가를 한 Lua 실행으로 원자 수행한다.
     * {@code logicalExpiringSortedSetKey}에는 값과 같은 TTL을 매번 PEXPIRE로 갱신한다 — 마지막 추가 뒤
     * TTL 동안 비활성인 key만 자연 소멸한다(member별 TTL이 아니다).
     *
     * <p>세 키 모두 prefix 없는 논리 키이며, score는 epoch milliseconds처럼 호출부가 정한 단위를 쓴다.
     */
    public void setAndAddToSortedSets(String logicalValueKey, String value, Duration ttl,
                                      String logicalSortedSetKey, String logicalExpiringSortedSetKey,
                                      String member, long score) {
        Long result = template.execute(SET_AND_SORTED_SETS_ADD,
                List.of(prefix + logicalValueKey, prefix + logicalSortedSetKey,
                        prefix + logicalExpiringSortedSetKey),
                String.valueOf(ttl.toMillis()), value, String.valueOf(score), member);
        requireScriptResult(result, logicalValueKey);
    }

    /** TTL 값 저장과 두 sorted set의 member 제거를 한 Lua 실행으로 원자 수행한다(TTL은 연장하지 않음). */
    public void setAndRemoveFromSortedSets(String logicalValueKey, String value, Duration ttl,
                                           String logicalFirstSortedSetKey, String logicalSecondSortedSetKey,
                                           String member) {
        Long result = template.execute(SET_AND_SORTED_SETS_REMOVE,
                List.of(prefix + logicalValueKey, prefix + logicalFirstSortedSetKey,
                        prefix + logicalSecondSortedSetKey),
                String.valueOf(ttl.toMillis()), value, member);
        requireScriptResult(result, logicalValueKey);
    }

    /**
     * sorted set 전체 member를 score 내림차순으로 반환한다(ZREVRANGE 0 -1). 같은 score의 member는
     * Redis reverse range 계약대로 역 lexicographic 순서다. key가 없으면 빈 목록이다.
     */
    public List<String> getSortedSetReverseRange(String logicalSortedSetKey) {
        Set<String> members = template.opsForZSet().reverseRange(prefix + logicalSortedSetKey, 0, -1);
        if (members == null) {
            // 파이프라인/트랜잭션 맥락에서만 null — 이 gateway는 그 맥락을 지원하지 않으므로 불변식 위반.
            throw new IllegalStateException("Redis reverseRange가 null을 반환했습니다: " + logicalSortedSetKey);
        }
        return List.copyOf(members);
    }

    /**
     * 여러 값을 한 명령(MGET)으로 읽는다. 반환 목록은 요청 키 순서와 index가 정렬돼 있으며,
     * 없는 키 자리는 null이다.
     */
    public List<String> multiGet(List<String> logicalKeys) {
        List<String> values = template.opsForValue()
                .multiGet(logicalKeys.stream().map(key -> prefix + key).toList());
        if (values == null) {
            // 파이프라인/트랜잭션 맥락에서만 null — 이 gateway는 그 맥락을 지원하지 않으므로 불변식 위반.
            throw new IllegalStateException("Redis multiGet이 null을 반환했습니다");
        }
        return values;
    }

    /** sorted set에서 여러 member를 한 명령으로 제거하고 실제 제거 수를 반환한다(missing member는 무시). */
    public long removeFromSortedSet(String logicalSortedSetKey, List<String> members) {
        Long removed = template.opsForZSet()
                .remove(prefix + logicalSortedSetKey, members.toArray(new Object[0]));
        if (removed == null) {
            // 파이프라인/트랜잭션 맥락에서만 null — 이 gateway는 그 맥락을 지원하지 않으므로 불변식 위반.
            throw new IllegalStateException("Redis zrem이 null을 반환했습니다: " + logicalSortedSetKey);
        }
        return removed;
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
