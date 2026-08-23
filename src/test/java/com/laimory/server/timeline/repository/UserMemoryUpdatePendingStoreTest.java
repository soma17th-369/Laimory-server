package com.laimory.server.timeline.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static com.laimory.server.testsupport.TestSubjects.id;

import com.laimory.server.common.redis.RedisGateway;
import com.laimory.server.timeline.entity.UserMemoryUpdatePending;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 미반영 작업 큐 단위테스트(인프라 없음). 환경 prefix 부착은 RedisGateway 책임이라 논리 키만 확인한다.
 *
 * <p>고정하는 계약 셋:
 * <ul>
 *   <li><b>넣을 때도 만료분을 걷어낸다</b> — 청소가 읽기에만 있으면 읽는 주체(배치)가 멈춘 사이 넣기만
 *       하고 지우는 사람이 없어 key가 무한히 자란다.</li>
 *   <li><b>청소가 읽기보다 먼저다</b> — 순서가 뒤집히면 만료된 날이 그 실행에서 한 번 더 접수된다.</li>
 *   <li><b>자르기 전 개수를 함께 준다</b> — 조회 상한에 걸려도 적체가 얼마인지 알아야 경고할 수 있다.
 *       개수와 목록이 같은 상한(대기 시작 시각 <= now)을 쓰므로 잘리지 않았다면 둘이 같다.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class UserMemoryUpdatePendingStoreTest {

    private static final Instant NOW = Instant.parse("2026-08-05T12:00:00Z");
    private static final Duration RETENTION = Duration.ofDays(30);
    /** 이 시각 이전에 대기를 시작한 항목은 retention을 넘겼다. */
    private static final long GIVE_UP_BEFORE = NOW.minus(RETENTION).toEpochMilli();
    private static final int LIMIT = 3;
    private static final UUID SUBJECT = id(7L);
    private static final UUID OTHER_SUBJECT = id(13L);

    @Mock
    private RedisGateway redis;

    private UserMemoryUpdatePendingStore store;

    @BeforeEach
    void setUp() {
        store = new UserMemoryUpdatePendingStore(redis, RETENTION);
    }

    @Test
    void 넣을_때도_만료분을_걷어내고_key_TTL을_갱신한다() {
        store.enqueue(new UserMemoryUpdatePending(SUBJECT, 42L), NOW);

        // 청소를 먼저 해야 방금 넣은 member가 같은 실행의 청소에 휩쓸릴 여지가 없다.
        InOrder inOrder = inOrder(redis);
        inOrder.verify(redis).pruneSortedSetByScore(UserMemoryUpdatePendingStore.PENDING_KEY, GIVE_UP_BEFORE);
        inOrder.verify(redis).addToSortedSetIfAbsent(
                UserMemoryUpdatePendingStore.PENDING_KEY, member(SUBJECT, 42L), NOW.toEpochMilli());
        inOrder.verify(redis).expire(UserMemoryUpdatePendingStore.PENDING_KEY, RETENTION);
    }

    @Test
    void 만료분을_걷어낸_뒤_상한만큼_읽고_자르기_전_개수를_함께_준다() {
        when(redis.getSortedSetRangeByScore(
                UserMemoryUpdatePendingStore.PENDING_KEY, NOW.toEpochMilli(), LIMIT))
                .thenReturn(List.of(member(SUBJECT, 42L), member(SUBJECT, 43L), member(OTHER_SUBJECT, 88L)));
        when(redis.countSortedSetByScore(UserMemoryUpdatePendingStore.PENDING_KEY, NOW.toEpochMilli()))
                .thenReturn(9L);

        UserMemoryUpdatePendingStore.PendingScan scan = store.findPending(NOW, LIMIT);

        // 개수가 읽어온 수보다 크다 = 상한에 잘렸다. 두 값이 같은 대기 시작 상한을 써야 이 해석이 성립한다.
        assertThat(scan.total()).isEqualTo(9);
        assertThat(scan.scanned()).containsExactly(
                new UserMemoryUpdatePending(SUBJECT, 42L),
                new UserMemoryUpdatePending(SUBJECT, 43L),
                new UserMemoryUpdatePending(OTHER_SUBJECT, 88L));

        InOrder inOrder = inOrder(redis);
        inOrder.verify(redis).pruneSortedSetByScore(UserMemoryUpdatePendingStore.PENDING_KEY, GIVE_UP_BEFORE);
        inOrder.verify(redis).getSortedSetRangeByScore(anyString(), anyLong(), anyLong());
    }

    @Test
    void 대기_항목이_없으면_개수만_돌아오고_제거를_호출하지_않는다() {
        when(redis.getSortedSetRangeByScore(anyString(), anyLong(), anyLong())).thenReturn(List.of());

        UserMemoryUpdatePendingStore.PendingScan scan = store.findPending(NOW, LIMIT);

        assertThat(scan.total()).isZero();
        assertThat(scan.scanned()).isEmpty();
        verify(redis, never()).removeFromSortedSet(anyString(), anyList());
    }

    @Test
    void 형식이_깨진_member는_결과에서_빼고_즉시_제거한다() {
        when(redis.getSortedSetRangeByScore(anyString(), anyLong(), anyLong()))
                .thenReturn(List.of(member(SUBJECT, 42L), "broken", "invalid:x"));
        when(redis.countSortedSetByScore(anyString(), anyLong())).thenReturn(3L);

        UserMemoryUpdatePendingStore.PendingScan scan = store.findPending(NOW, LIMIT);

        // 되살아나면 매 실행마다 같은 쓰레기를 다시 읽는다.
        assertThat(scan.scanned()).containsExactly(new UserMemoryUpdatePending(SUBJECT, 42L));
        verify(redis).removeFromSortedSet(
                UserMemoryUpdatePendingStore.PENDING_KEY, List.of("broken", "invalid:x"));
    }

    @Test
    void 반영된_날들은_member를_복원해_한_번에_지운다() {
        store.removeAll(SUBJECT, List.of(42L, 43L));

        // 결과 endpoint는 task의 dailyRecordIds만 들고 있으므로 member를 식별자로 복원할 수 있어야 한다.
        verify(redis).removeFromSortedSet(
                UserMemoryUpdatePendingStore.PENDING_KEY, List.of(member(SUBJECT, 42L), member(SUBJECT, 43L)));
    }

    private static String member(UUID subjectId, long dailyRecordId) {
        return subjectId + ":" + dailyRecordId;
    }
}
