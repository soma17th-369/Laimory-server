package com.laimory.server.timeline.repository;

import com.laimory.server.common.redis.RedisGateway;
import com.laimory.server.timeline.entity.UserMemoryUpdatePending;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * User Memory 갱신의 사용자 단위 guard와 미반영 작업 큐의 Redis 데이터 접근 계층.
 *
 * <p>논리 키: guard는 {@code timeline:user-memory-update:user:{userId}}(SET NX, TTL),
 * 큐는 sorted set {@code timeline:user-memory-update:pending}
 * (member: {@code userId:dailyRecordId}, score: <b>최초</b> 기록 시각 epoch ms). 환경 prefix는
 * {@link RedisGateway}가 붙인다.
 *
 * <p><b>큐에 있는 것은 전부 "아직 반영 안 된 날"이다.</b> 경합 없이 접수돼 반영까지 끝난 날은 여기 들어오지
 * 않는다. 넣는 지점은 둘이다 — 사용자 guard를 못 잡았을 때, 그리고 AI가 실패를 통보했을 때. 빼는 지점도
 * 둘이다 — 반영이 확인됐을 때, 그리고 다시 보내도 소용없는 실패(4xx·재료 없음)일 때.
 *
 * <p>재기록은 score를 <b>갱신하지 않는다</b>(ZADD NX). 최초 기록 시각이 밀리면 age 기반 포기 시한이
 * 무한히 연장돼 영영 안 되는 날이 큐에 남는다.
 *
 * <p>member는 JSON이 아니라 고정 형식 문자열이다 — 같은 작업을 두 번 담지 않으려면(ZADD가 member
 * 동일성으로 판정) 필드 순서가 흔들리는 직렬화를 쓸 수 없다. 식별자만으로 만들어지므로 결과 endpoint가
 * task의 {@code dailyRecordIds}만으로 같은 member를 복원할 수 있다.
 */
@Slf4j
@Component
public class UserMemoryUpdatePendingStore {

    static final String PENDING_KEY = "timeline:user-memory-update:pending";
    private static final String GUARD_KEY_PREFIX = "timeline:user-memory-update:user:";
    private static final String MEMBER_DELIMITER = ":";
    private static final int MEMBER_FIELD_COUNT = 2;

    private final RedisGateway redis;
    private final Duration retention;

    // retention 프로퍼티 주입이 있어 @RequiredArgsConstructor 대신 명시적 생성자를 쓴다.
    public UserMemoryUpdatePendingStore(
            RedisGateway redis,
            @Value("${app.user-memory.update.retention:7d}") Duration retention) {
        this.redis = redis;
        this.retention = retention;
    }

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

    /** 아직 반영되지 않은 날을 큐에 남긴다. 이미 있으면 최초 기록 시각을 유지한다. */
    public void enqueue(UserMemoryUpdatePending pending, Instant recordedAt) {
        redis.addToSortedSetIfAbsent(PENDING_KEY, member(pending), recordedAt.toEpochMilli());
    }

    public void enqueueAll(long userId, List<Long> dailyRecordIds, Instant recordedAt) {
        dailyRecordIds.forEach(dailyRecordId ->
                enqueue(new UserMemoryUpdatePending(userId, dailyRecordId), recordedAt));
    }

    public void remove(UserMemoryUpdatePending pending) {
        redis.removeFromSortedSet(PENDING_KEY, List.of(member(pending)));
    }

    public void removeAll(long userId, List<Long> dailyRecordIds) {
        redis.removeFromSortedSet(PENDING_KEY, dailyRecordIds.stream()
                .map(dailyRecordId -> member(new UserMemoryUpdatePending(userId, dailyRecordId)))
                .toList());
    }

    /**
     * 아직 시한이 남은 작업을 오래된 것부터 최대 {@code limit}개 반환한다.
     * 시한({@code retention})을 넘긴 항목과 형식이 깨진 member는 되살아나지 않도록 즉시 제거한다.
     */
    public List<UserMemoryUpdatePending> findPending(Instant now, int limit) {
        long expiredBefore = now.minus(retention).toEpochMilli();
        redis.pruneAndCountSortedSet(PENDING_KEY, expiredBefore, expiredBefore);

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
            log.warn("User Memory 갱신 미반영 작업 형식 오류로 폐기: count={}", malformed.size());
            redis.removeFromSortedSet(PENDING_KEY, malformed);
        }
        return List.copyOf(pending);
    }

    static String guardKey(long userId) {
        return GUARD_KEY_PREFIX + userId;
    }

    private static String member(UserMemoryUpdatePending pending) {
        return pending.userId() + MEMBER_DELIMITER + pending.dailyRecordId();
    }

    private static UserMemoryUpdatePending parse(String member) {
        String[] fields = member.split(MEMBER_DELIMITER);
        if (fields.length != MEMBER_FIELD_COUNT) {
            return null;
        }
        try {
            return new UserMemoryUpdatePending(Long.parseLong(fields[0]), Long.parseLong(fields[1]));
        } catch (IllegalArgumentException e) {
            // 숫자 파싱 실패와 record compact 생성자의 범위 검증 실패를 함께 받는다.
            return null;
        }
    }
}
