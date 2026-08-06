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
 * User Memory 갱신 대기 큐와 사용자 단위 guard의 Redis 데이터 접근 계층.
 *
 * <p>논리 키: 대기 큐는 sorted set {@code timeline:user-memory-update:pending}
 * (member: {@code userId:dailyRecordId:deadlineEpochMillis}, score: 등록 시각 epoch ms — 오래된 것부터
 * 드레인한다), guard는 {@code timeline:user-memory-update:user:{userId}}. 환경 prefix는
 * {@link RedisGateway}가 붙인다.
 *
 * <p>저장 직후 즉시 접수가 guard를 잡으면 항목은 곧바로 큐에서 빠진다. 큐에 머무르는 것은 <b>경합으로
 * 못 넘어간 항목</b>과 프로세스 종료로 즉시 접수가 유실된 항목뿐이고, 하루 1회 배치가 이들을 줍는다.
 *
 * <p><b>guard가 이 store에 있는 이유</b>: guard 획득이 곧 대기 큐에서의 claim이라 둘은 한 원자 경계여야
 * 한다. 나누면 인스턴스 둘이 같은 항목을 읽었을 때 진 쪽의 재배치가 이긴 쪽의 제거 뒤에 도착해 같은
 * 날짜가 두 번 접수될 수 있다.
 *
 * <p>member는 JSON이 아니라 고정 형식 문자열이다 — score 갱신(ZADD)이 member 동일성에 기대므로 필드
 * 순서가 흔들리는 직렬화를 쓸 수 없다.
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

    /** 대기 등록. 같은 항목이 이미 있으면 score만 갱신된다. */
    public void enqueue(UserMemoryUpdatePending pending, Instant readyAt) {
        redis.addToSortedSet(PENDING_KEY, member(pending), readyAt.toEpochMilli());
    }

    public void remove(UserMemoryUpdatePending pending) {
        redis.removeFromSortedSet(PENDING_KEY, List.of(member(pending)));
    }

    /**
     * 등록된 항목을 오래된 것부터 최대 {@code limit}개 반환한다.
     * 형식이 깨진 member는 되살아나지 않도록 즉시 제거하고 결과에서 제외한다.
     */
    public List<UserMemoryUpdatePending> findReady(Instant now, int limit) {
        List<String> members = redis.getSortedSetRangeByScore(PENDING_KEY, now.toEpochMilli(), limit);
        if (members.isEmpty()) {
            return List.of();
        }
        List<UserMemoryUpdatePending> ready = new ArrayList<>(members.size());
        List<String> malformed = new ArrayList<>();
        for (String member : members) {
            UserMemoryUpdatePending pending = parse(member);
            if (pending == null) {
                malformed.add(member);
                continue;
            }
            ready.add(pending);
        }
        if (!malformed.isEmpty()) {
            log.warn("User Memory 갱신 대기 항목 형식 오류로 폐기: count={}", malformed.size());
            redis.removeFromSortedSet(PENDING_KEY, malformed);
        }
        return List.copyOf(ready);
    }

    /**
     * 사용자 guard를 잡고 같은 실행에서 대기 큐의 항목을 가져간다. 이미 다른 날짜가 그 사용자의 갱신을
     * 진행 중이면 {@code false}이고 대기 항목은 큐에 그대로 남는다(하루 1회 배치가 다시 시도한다).
     *
     * @param guardValue 진단용으로 guard에 남길 값 — 이 작업의 taskId를 쓴다
     */
    public boolean claim(UserMemoryUpdatePending pending, String guardValue, Duration guardTtl) {
        return redis.setIfAbsentAndRemoveFromSortedSet(
                guardKey(pending.userId()), guardValue, guardTtl, PENDING_KEY, member(pending));
    }

    /** 작업 종결 시 guard 반납. 실패해도 TTL이 정리하므로 호출부는 best-effort로 다룬다. */
    public void releaseGuard(long userId) {
        redis.delete(guardKey(userId));
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
