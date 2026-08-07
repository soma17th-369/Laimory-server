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
 * 아직 User Memory에 반영되지 않은 날의 큐.
 *
 * <p>논리 키: sorted set {@code timeline:user-memory-update:pending}
 * (member: {@code userId:dailyRecordId}, score: <b>최초</b> 기록 시각 epoch ms). 환경 prefix는
 * {@link RedisGateway}가 붙인다.
 *
 * <p><b>큐에 있는 것은 전부 "아직 반영 안 된 날"이다.</b> 경합 없이 접수돼 반영까지 끝난 날은 여기 들어오지
 * 않는다. 넣는 지점은 접수되지 못한 모든 경우(guard 점유·4xx 거절·결과 불명)와 AI의 실패 통보이고, 빼는
 * 지점은 반영 확인과 갱신할 재료가 사라졌을 때다.
 *
 * <p>재기록은 score를 <b>갱신하지 않는다</b>(ZADD NX). 최초 기록 시각이 밀리면 age 기반 포기 시한이
 * 무한히 연장돼 영영 안 되는 날이 큐에 남는다. 그래서 {@code retention}은 <b>최초 진입 기준 절대
 * 시한</b>이고, 큐에 있는 항목은 배치가 매일 다시 접수하므로 이 값이 곧 재시도 기간이다 — 실패마다
 * 시한을 리셋하는 것과 결과가 같으면서 무한 잔류만 없다.
 *
 * <p><b>만료분 청소는 읽기({@link #findPending})와 쓰기({@link #enqueue}) 양쪽에 있다.</b> 읽기에만
 * 두면 배치가 멈춘 사이 key가 무한히 자란다 — 저장 API는 배치와 무관하게 계속 돌고 접수 실패는 계속
 * 들어오기 때문이다. 양쪽에 두면 key 크기가 "retention 동안의 유입량"으로 상한이 잡힌다. key TTL도
 * 매번 갱신하지만 그건 트래픽이 완전히 끊긴 환경의 최후 정리일 뿐, 성장을 막는 것은 prune이다.
 *
 * <p>member는 JSON이 아니라 고정 형식 문자열이다 — 같은 작업을 두 번 담지 않으려면(ZADD가 member
 * 동일성으로 판정) 필드 순서가 흔들리는 직렬화를 쓸 수 없다. 식별자만으로 만들어지므로 결과 endpoint가
 * task의 {@code dailyRecordIds}만으로 같은 member를 복원할 수 있다.
 */
@Slf4j
@Component
public class UserMemoryUpdatePendingStore {

    static final String PENDING_KEY = "timeline:user-memory-update:pending";
    private static final String MEMBER_DELIMITER = ":";
    private static final int MEMBER_FIELD_COUNT = 2;

    private final RedisGateway redis;
    private final Duration retention;

    // retention 프로퍼티 주입이 있어 @RequiredArgsConstructor 대신 명시적 생성자를 쓴다.
    public UserMemoryUpdatePendingStore(
            RedisGateway redis,
            @Value("${app.user-memory.update.retention:30d}") Duration retention) {
        this.redis = redis;
        this.retention = retention;
    }

    /**
     * 아직 반영되지 않은 날을 큐에 남긴다. 이미 있으면 최초 기록 시각을 유지한다.
     * 넣는 김에 같은 실행으로 만료분을 걷어내고 key TTL을 갱신한다.
     */
    public void enqueue(UserMemoryUpdatePending pending, Instant recordedAt) {
        // TTL == retention이라 만료 시점에 남아 있는 member는 모두 retention보다 오래됐다(어차피 폐기 대상).
        redis.addToSortedSetIfAbsentAndPrune(PENDING_KEY, member(pending), recordedAt.toEpochMilli(),
                recordedAt.minus(retention).toEpochMilli(), retention);
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
     *
     * <p>{@code limit}은 처리량 제한이 아니라 <b>단일 응답 크기의 안전선</b>이다. 상한 없이 읽으면 큐가
     * 커졌을 때 한 번의 명령이 수 MB를 끌어오고, Redis는 싱글스레드라 그동안 다른 명령이 밀린다. 평상시엔
     * 걸리지 않을 만큼 크게 잡고, 걸리면 {@link PendingScan#total()}과 비교해 호출부가 경고한다.
     *
     * <p>{@link PendingScan#total()}은 <b>자르기 전</b> 큐 전체 크기다 — 잘린 채로도 적체를 알 수 있어야 한다.
     */
    public PendingScan findPending(Instant now, int limit) {
        List<String> result = redis.pruneAndReadSortedSet(PENDING_KEY,
                now.minus(retention).toEpochMilli(), now.toEpochMilli(), limit);

        long total = parseTotal(result.getFirst());
        List<UserMemoryUpdatePending> pending = new ArrayList<>(result.size() - 1);
        List<String> malformed = new ArrayList<>();
        for (String member : result.subList(1, result.size())) {
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
        return new PendingScan(total, List.copyOf(pending));
    }

    /**
     * @param total   {@code limit}으로 자르기 전 큐 전체 크기(적체 관측용)
     * @param scanned 이번에 읽어온 것(오래된 순, 최대 {@code limit}개)
     */
    public record PendingScan(long total, List<UserMemoryUpdatePending> scanned) {
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

    private static long parseTotal(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            // 첫 원소는 스크립트가 항상 zcard로 채운다 — 숫자가 아니면 gateway 계약 위반이다.
            throw new IllegalStateException("대기 큐 개수를 읽지 못했습니다: " + value, e);
        }
    }
}
