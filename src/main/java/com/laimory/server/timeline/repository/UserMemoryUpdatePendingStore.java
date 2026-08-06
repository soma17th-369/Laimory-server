package com.laimory.server.timeline.repository;

import com.laimory.server.common.redis.RedisGateway;
import com.laimory.server.timeline.entity.UserMemoryUpdatePending;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * User Memory 갱신의 사용자 단위 guard와 밀린 작업 큐의 Redis 데이터 접근 계층.
 *
 * <p>논리 키: guard는 {@code timeline:user-memory-update:user:{userId}}(SET NX, TTL),
 * 밀린 작업 큐는 sorted set {@code timeline:user-memory-update:pending}
 * (member: {@code userId:dailyRecordId:deadlineEpochMillis}, score: 기록 시각 epoch ms — 오래된 것부터
 * 드레인한다). 환경 prefix는 {@link RedisGateway}가 붙인다.
 *
 * <p><b>큐는 DLQ다.</b> 저장 직후 즉시 접수가 guard를 잡으면 큐는 아예 쓰이지 않는다 — 여기 쌓이는 것은
 * <b>guard 충돌로 넘어가지 못한 작업</b>뿐이고, 하루 1회 배치가 이들만 처리한다. guard 획득 자체가
 * "그 사용자의 갱신이 진행 중인가"를 판정하므로 별도의 진행 상태 저장이 필요 없다.
 *
 * <p>member는 JSON이 아니라 고정 형식 문자열이다 — 같은 작업을 두 번 기록하지 않으려면(ZADD가 member
 * 동일성으로 판정) 필드 순서가 흔들리는 직렬화를 쓸 수 없다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserMemoryUpdatePendingStore {

    static final String PENDING_KEY = "timeline:user-memory-update:pending";
    private static final String GUARD_KEY_PREFIX = "timeline:user-memory-update:user:";
    private static final String MEMBER_DELIMITER = ":";
    private static final int MEMBER_FIELD_COUNT = 3;

    private final RedisGateway redis;

    /**
     * 사용자 갱신 guard를 잡는다. 실패는 <b>그 사용자의 다른 갱신이 진행 중</b>이라는 뜻이고, 호출부는
     * 그 작업을 {@link #enqueue}로 큐에 남긴다.
     *
     * @param taskId 진단용으로 guard에 남길 값
     */
    public boolean acquireGuard(long userId, String taskId, Duration ttl) {
        return redis.setIfAbsent(guardKey(userId), taskId, ttl);
    }

    /** 작업 종결 시 guard 반납. 실패해도 TTL이 정리하므로 호출부는 best-effort로 다룬다. */
    public void releaseGuard(long userId) {
        redis.delete(guardKey(userId));
    }

    /** guard 충돌로 넘어가지 못한 작업을 큐에 남긴다. 같은 작업을 다시 넣으면 score만 갱신된다. */
    public void enqueue(UserMemoryUpdatePending pending, Instant recordedAt) {
        redis.addToSortedSet(PENDING_KEY, member(pending), recordedAt.toEpochMilli());
    }

    public void remove(UserMemoryUpdatePending pending) {
        redis.removeFromSortedSet(PENDING_KEY, List.of(member(pending)));
    }

    /**
     * 밀린 작업을 오래된 것부터 최대 {@code limit}개 반환한다.
     * 형식이 깨진 member는 되살아나지 않도록 즉시 제거하고 결과에서 제외한다.
     */
    public List<UserMemoryUpdatePending> findPending(Instant now, int limit) {
        List<String> members = redis.getSortedSetRangeByScore(PENDING_KEY, now.toEpochMilli(), limit);
        if (members.isEmpty()) {
            return List.of();
        }
        List<UserMemoryUpdatePending> pending = new ArrayList<>(members.size());
        List<String> malformed = new ArrayList<>();
        for (String member : members) {
            UserMemoryUpdatePending parsed = parse(member);
            if (parsed == null) {
                malformed.add(member);
                continue;
            }
            pending.add(parsed);
        }
        if (!malformed.isEmpty()) {
            log.warn("User Memory 갱신 밀린 작업 형식 오류로 폐기: count={}", malformed.size());
            redis.removeFromSortedSet(PENDING_KEY, malformed);
        }
        return List.copyOf(pending);
    }

    static String guardKey(long userId) {
        return GUARD_KEY_PREFIX + userId;
    }

    private static String member(UserMemoryUpdatePending pending) {
        return pending.userId() + MEMBER_DELIMITER + pending.dailyRecordId()
                + MEMBER_DELIMITER + pending.deadline().toEpochMilli();
    }

    private static UserMemoryUpdatePending parse(String member) {
        String[] fields = member.split(MEMBER_DELIMITER);
        if (fields.length != MEMBER_FIELD_COUNT) {
            return null;
        }
        try {
            return new UserMemoryUpdatePending(
                    Long.parseLong(fields[0]),
                    Long.parseLong(fields[1]),
                    Instant.ofEpochMilli(Long.parseLong(fields[2])));
        } catch (IllegalArgumentException e) {
            // 숫자 파싱 실패와 record compact 생성자의 범위 검증 실패를 함께 받는다.
            return null;
        }
    }
}
