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

    // token/stage와 terminal 전이는 task JSON 한 key만 compare-and-set한다. 보조 processing index는
    // task write 성공 뒤 native command로 갱신·보정하므로 script의 원자 경계에 포함하지 않는다.
    private static final RedisScript<Long> COMPARE_AND_SET = new DefaultRedisScript<>("""
            if redis.call('get', KEYS[1]) ~= ARGV[1] then
                return 0
            end
            redis.call('set', KEYS[1], ARGV[2], 'PX', ARGV[3])
            return 1
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

    /** 현재 값이 {@code expectedValue}와 같을 때만 새 값과 TTL로 교체한다. missing key는 실패한다. */
    public boolean compareAndSet(String logicalKey, String expectedValue, String newValue, Duration ttl) {
        Long result = template.execute(COMPARE_AND_SET,
                List.of(prefix + logicalKey), expectedValue, newValue, String.valueOf(ttl.toMillis()));
        return requireScriptResult(result, logicalKey) == 1;
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

    /**
     * key가 없을 때만 값을 저장한다(SET NX PX). 상호 배제 lock 획득용으로, 반환값이 곧 획득 성공 여부다.
     * TTL이 있어 소유자가 죽어도 자동으로 풀린다.
     */
    public boolean setIfAbsent(String logicalKey, String value, Duration ttl) {
        return Boolean.TRUE.equals(template.opsForValue().setIfAbsent(prefix + logicalKey, value, ttl));
    }

    // ── 대기 큐용 sorted set 연산 ──
    // 이름은 Redis 용어(score) 그대로 둔다. 도메인 의미(대기 시작 시각 등)는 호출부가 이름 붙인다.
    //
    // 다만 이 gateway를 거치는 sorted set은 score를 epoch milliseconds로만 쓴다는 것이 계약이다.
    // Redis의 score는 double(가수 53비트)이라 정수는 2^53(≈9.0e15)까지만 정확한데, epoch ms는 그 한계에
    // 서기 285,000년경 닿으므로 사실상 무한하다. microseconds면 서기 2255년, nanoseconds면 1970년 4개월
    // 뒤부터 값이 뭉개진다 — 정밀도를 잃어도 예외가 나지 않고 순서만 조용히 어긋나므로 단위를 못 박는다.

    /**
     * sorted set에 member가 없을 때만 넣는다(ZADD NX). 이미 있는 member는 score를 <b>유지한다</b> —
     * 재기록으로 시각이 밀려 age 기반 만료가 무한 연장되는 것을 막는다.
     */
    public void addToSortedSetIfAbsent(String logicalSortedSetKey, String member, long score) {
        template.opsForZSet().addIfAbsent(prefix + logicalSortedSetKey, member, score);
    }

    /** sorted set member를 추가하거나 기존 member의 score를 갱신한다(ZADD). */
    public void addToSortedSet(String logicalSortedSetKey, String member, long score) {
        Boolean added = template.opsForZSet().add(prefix + logicalSortedSetKey, member, score);
        if (added == null) {
            throw new IllegalStateException("Redis zadd가 null을 반환했습니다: " + logicalSortedSetKey);
        }
    }

    /** {@code score <= expiredScore}인 member를 제거하고 제거 수를 반환한다(ZREMRANGEBYSCORE). */
    public long pruneSortedSetByScore(String logicalSortedSetKey, long expiredScore) {
        Long removed = template.opsForZSet()
                .removeRangeByScore(prefix + logicalSortedSetKey, Double.NEGATIVE_INFINITY, expiredScore);
        if (removed == null) {
            // 파이프라인/트랜잭션 맥락에서만 null — 이 gateway는 그 맥락을 지원하지 않으므로 불변식 위반.
            throw new IllegalStateException("Redis zremrangebyscore가 null을 반환했습니다: " + logicalSortedSetKey);
        }
        return removed;
    }

    /**
     * {@code score <= inclusiveMaxScore}인 member를 score <b>오름차순</b>으로 최대 {@code limit}개
     * 반환한다. key가 없으면 빈 목록이다.
     */
    public List<String> getSortedSetRangeByScore(String logicalSortedSetKey, long inclusiveMaxScore, long limit) {
        Set<String> members = template.opsForZSet().rangeByScore(
                prefix + logicalSortedSetKey, Double.NEGATIVE_INFINITY, inclusiveMaxScore, 0, limit);
        if (members == null) {
            // 파이프라인/트랜잭션 맥락에서만 null — 이 gateway는 그 맥락을 지원하지 않으므로 불변식 위반.
            throw new IllegalStateException("Redis rangeByScore가 null을 반환했습니다: " + logicalSortedSetKey);
        }
        return List.copyOf(members);
    }

    /** {@code score <= inclusiveMaxScore}인 member 수를 센다(ZCOUNT). 조회와 같은 상한을 써야 비교가 성립한다. */
    public long countSortedSetByScore(String logicalSortedSetKey, long inclusiveMaxScore) {
        Long count = template.opsForZSet()
                .count(prefix + logicalSortedSetKey, Double.NEGATIVE_INFINITY, inclusiveMaxScore);
        if (count == null) {
            // 파이프라인/트랜잭션 맥락에서만 null — 이 gateway는 그 맥락을 지원하지 않으므로 불변식 위반.
            throw new IllegalStateException("Redis zcount가 null을 반환했습니다: " + logicalSortedSetKey);
        }
        return count;
    }

    /** key에 TTL을 다시 걸고 성공 여부를 반환한다(PEXPIRE). key가 없으면 false다. */
    public boolean expire(String logicalKey, Duration ttl) {
        return Boolean.TRUE.equals(template.expire(prefix + logicalKey, ttl));
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

    private static long requireScriptResult(Long result, String logicalKey) {
        if (result == null) {
            // 파이프라인/트랜잭션 맥락에서만 null — 이 gateway는 그 맥락을 지원하지 않으므로 불변식 위반.
            throw new IllegalStateException("Redis script가 null을 반환했습니다: " + logicalKey);
        }
        return result;
    }
}
