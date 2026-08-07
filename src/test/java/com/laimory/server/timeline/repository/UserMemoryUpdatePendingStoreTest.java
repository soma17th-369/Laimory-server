package com.laimory.server.timeline.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.laimory.server.common.redis.RedisGateway;
import com.laimory.server.timeline.entity.UserMemoryUpdatePending;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 미반영 작업 큐 조회 단위테스트(인프라 없음). 환경 prefix 부착은 RedisGateway 책임이라 논리 키만 확인한다.
 *
 * <p>가장 중요한 계약은 <b>건수 상한 없이 전부 읽는다</b>는 것이다 — 호출부가 사용자별로 묶어 사용자당 한
 * 번만 접수하므로, 건수로 자르면 잘려 나간 사용자가 이유 없이 다음 실행까지 밀린다.
 */
@ExtendWith(MockitoExtension.class)
class UserMemoryUpdatePendingStoreTest {

    private static final Instant NOW = Instant.parse("2026-08-05T12:00:00Z");
    private static final Duration RETENTION = Duration.ofDays(7);

    @Mock
    private RedisGateway redis;

    private UserMemoryUpdatePendingStore store;

    @BeforeEach
    void setUp() {
        store = new UserMemoryUpdatePendingStore(redis, RETENTION);
    }

    @Test
    void 대기_항목을_건수_상한_없이_전부_읽는다() {
        when(redis.getSortedSetRangeByScore(UserMemoryUpdatePendingStore.PENDING_KEY, NOW.toEpochMilli()))
                .thenReturn(List.of("7:42", "7:43", "13:88"));

        List<UserMemoryUpdatePending> pending = store.findPending(NOW);

        assertThat(pending).containsExactly(
                new UserMemoryUpdatePending(7L, 42L),
                new UserMemoryUpdatePending(7L, 43L),
                new UserMemoryUpdatePending(13L, 88L));
    }

    @Test
    void 만료분을_먼저_걷어낸_뒤_조회한다() {
        long expiredBefore = NOW.minus(RETENTION).toEpochMilli();
        when(redis.getSortedSetRangeByScore(UserMemoryUpdatePendingStore.PENDING_KEY, NOW.toEpochMilli()))
                .thenReturn(List.of("7:42"));

        store.findPending(NOW);

        // 순서가 뒤집히면 이번 회차가 이미 시한을 넘긴 항목까지 접수한다.
        InOrder order = inOrder(redis);
        order.verify(redis).pruneAndCountSortedSet(
                UserMemoryUpdatePendingStore.PENDING_KEY, expiredBefore, expiredBefore);
        order.verify(redis).getSortedSetRangeByScore(
                UserMemoryUpdatePendingStore.PENDING_KEY, NOW.toEpochMilli());
    }

    @Test
    void 형식이_깨진_member는_결과에서_빼고_즉시_제거한다() {
        when(redis.getSortedSetRangeByScore(UserMemoryUpdatePendingStore.PENDING_KEY, NOW.toEpochMilli()))
                .thenReturn(List.of("7:42", "broken", "13:x"));

        List<UserMemoryUpdatePending> pending = store.findPending(NOW);

        // 되살아나면 매 실행마다 같은 쓰레기를 다시 읽는다.
        assertThat(pending).containsExactly(new UserMemoryUpdatePending(7L, 42L));
        verify(redis).removeFromSortedSet(
                UserMemoryUpdatePendingStore.PENDING_KEY, List.of("broken", "13:x"));
    }

    @Test
    void 대기_항목이_없으면_제거를_호출하지_않는다() {
        when(redis.getSortedSetRangeByScore(UserMemoryUpdatePendingStore.PENDING_KEY, NOW.toEpochMilli()))
                .thenReturn(List.of());

        assertThat(store.findPending(NOW)).isEmpty();
        verify(redis, never()).removeFromSortedSet(anyString(), anyList());
    }
}
