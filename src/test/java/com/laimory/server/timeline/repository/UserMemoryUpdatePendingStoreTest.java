package com.laimory.server.timeline.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 미반영 작업 큐 단위테스트(인프라 없음). 환경 prefix 부착은 RedisGateway 책임이라 논리 키만 확인한다.
 *
 * <p>고정하는 계약 둘:
 * <ul>
 *   <li><b>넣을 때도 만료분을 걷어낸다</b> — 청소가 읽기에만 있으면 읽는 주체(배치)가 멈춘 사이 넣기만
 *       하고 지우는 사람이 없어 key가 무한히 자란다.</li>
 *   <li><b>자르기 전 개수를 함께 준다</b> — 조회 상한에 걸려도 적체가 얼마인지 알아야 경고할 수 있다.
 *       개수는 목록과 같은 score 범위를 세므로(gateway 계약) 잘리지 않았다면 둘이 같다.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class UserMemoryUpdatePendingStoreTest {

    private static final Instant NOW = Instant.parse("2026-08-05T12:00:00Z");
    private static final Duration RETENTION = Duration.ofDays(30);
    private static final int LIMIT = 3;

    @Mock
    private RedisGateway redis;

    private UserMemoryUpdatePendingStore store;

    @BeforeEach
    void setUp() {
        store = new UserMemoryUpdatePendingStore(redis, RETENTION);
    }

    @Test
    void 넣을_때도_만료분을_걷어내고_key_TTL을_갱신한다() {
        store.enqueue(new UserMemoryUpdatePending(7L, 42L), NOW);

        verify(redis).addToSortedSetIfAbsentAndPrune(UserMemoryUpdatePendingStore.PENDING_KEY, "7:42",
                NOW.toEpochMilli(), NOW.minus(RETENTION).toEpochMilli(), RETENTION);
    }

    @Test
    void 만료분을_걷어낸_뒤_상한만큼_읽고_자르기_전_개수를_함께_준다() {
        // 첫 원소가 자르기 전 개수다 — 잘린 채로도 적체를 알 수 있어야 경고할 수 있다.
        when(redis.pruneAndReadSortedSet(UserMemoryUpdatePendingStore.PENDING_KEY,
                NOW.minus(RETENTION).toEpochMilli(), NOW.toEpochMilli(), LIMIT))
                .thenReturn(List.of("9", "7:42", "7:43", "13:88"));

        UserMemoryUpdatePendingStore.PendingScan scan = store.findPending(NOW, LIMIT);

        assertThat(scan.total()).isEqualTo(9);
        assertThat(scan.scanned()).containsExactly(
                new UserMemoryUpdatePending(7L, 42L),
                new UserMemoryUpdatePending(7L, 43L),
                new UserMemoryUpdatePending(13L, 88L));
    }

    @Test
    void 대기_항목이_없으면_개수만_돌아오고_제거를_호출하지_않는다() {
        when(redis.pruneAndReadSortedSet(anyString(), anyLong(), anyLong(), anyLong()))
                .thenReturn(List.of("0"));

        UserMemoryUpdatePendingStore.PendingScan scan = store.findPending(NOW, LIMIT);

        assertThat(scan.total()).isZero();
        assertThat(scan.scanned()).isEmpty();
        verify(redis, never()).removeFromSortedSet(anyString(), anyList());
    }

    @Test
    void 형식이_깨진_member는_결과에서_빼고_즉시_제거한다() {
        when(redis.pruneAndReadSortedSet(anyString(), anyLong(), anyLong(), anyLong()))
                .thenReturn(List.of("3", "7:42", "broken", "13:x"));

        UserMemoryUpdatePendingStore.PendingScan scan = store.findPending(NOW, LIMIT);

        // 되살아나면 매 실행마다 같은 쓰레기를 다시 읽는다.
        assertThat(scan.scanned()).containsExactly(new UserMemoryUpdatePending(7L, 42L));
        verify(redis).removeFromSortedSet(
                UserMemoryUpdatePendingStore.PENDING_KEY, List.of("broken", "13:x"));
    }

    @Test
    void 반영된_날들은_member를_복원해_한_번에_지운다() {
        store.removeAll(7L, List.of(42L, 43L));

        // 결과 endpoint는 task의 dailyRecordIds만 들고 있으므로 member를 식별자로 복원할 수 있어야 한다.
        verify(redis).removeFromSortedSet(
                UserMemoryUpdatePendingStore.PENDING_KEY, List.of("7:42", "7:43"));
    }
}
