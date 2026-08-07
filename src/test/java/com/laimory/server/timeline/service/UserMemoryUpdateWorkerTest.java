package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.timeline.TimelineEventType;
import com.laimory.server.timeline.UserMemoryDigest;
import com.laimory.server.timeline.dto.AiUserMemoryUpdateRequest;
import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.entity.TimelineEvent;
import com.laimory.server.timeline.entity.UserMemoryUpdatePending;
import com.laimory.server.timeline.entity.UserMemoryUpdateTask;
import com.laimory.server.timeline.repository.UserMemoryUpdatePendingStore;
import com.laimory.server.timeline.repository.UserMemoryUpdateTaskStore;
import com.laimory.server.user.UserMemoryService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * User Memory 갱신 접수 단위 검증.
 *
 * <p>가장 중요한 계약은 <b>조립이 guard 획득 뒤에 일어난다</b>는 것이다 — 대기 중에 앞선 날짜의 갱신이
 * User Memory를 바꾸므로, 미리 읽어 두면 낡은 문서를 base로 삼게 된다.
 *
 * <p>그 다음은 <b>큐를 언제 비우느냐</b>다. 저장된 하루는 예외 없이 큐를 거치고 접수는 배치가 전담하므로,
 * 접수 결과가 무엇이든 큐는 그대로 둔다 — 걷어내는 유일한 경우는 갱신할 재료(하루 기록)가 사라졌을 때이고
 * 일부만 사라진 경우도 같다. 나머지는 결과 endpoint가 반영을 확인하고 정리한다.
 */
@ExtendWith(MockitoExtension.class)
class UserMemoryUpdateWorkerTest {

    private static final long USER_ID = 7L;
    private static final long RECORD_ID = 42L;
    private static final Instant NOW = Instant.parse("2026-08-05T12:00:00Z");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private UserMemoryUpdatePendingStore pendingStore;
    @Mock
    private UserMemoryUpdateTaskStore taskStore;
    @Mock
    private DailyRecordService dailyRecordService;
    @Mock
    private TimelineEventService timelineEventService;
    @Mock
    private UserMemoryService userMemoryService;
    @Mock
    private UserMemoryUpdateDispatcher dispatcher;

    private UserMemoryUpdateWorker worker;

    @BeforeEach
    void setUp() {
        worker = new UserMemoryUpdateWorker(pendingStore, taskStore, dailyRecordService, timelineEventService,
                userMemoryService, dispatcher, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    // ── 저장 직후 큐 적재 ──

    @Test
    void 저장_직후에는_큐에_넣기만_하고_AI를_부르지_않는다() {
        UserMemoryUpdatePending pending = pending(RECORD_ID);

        worker.enqueue(pending);

        // 접수 성공이 반영 성공이 아니라, 큐를 거치지 않고 보낸 날은 결과가 끝내 안 올 때 재시도할 근거가 없다.
        verify(pendingStore).enqueue(pending, NOW);
        verifyNoInteractions(dispatcher, taskStore, userMemoryService, dailyRecordService);
    }

    // ── 하루 1회 배치 ──

    @Test
    void 조립은_guard를_잡은_뒤에_그_시점의_User_Memory를_읽는다() throws Exception {
        UserMemoryUpdatePending pending = pending(RECORD_ID);
        JsonNode currentMemory = objectMapper.readTree("{\"schemaVersion\":\"1.0\"}");
        stubPendingQueue(List.of(pending));
        stubClaimable(pending);
        when(userMemoryService.find(USER_ID)).thenReturn(Optional.of(currentMemory));

        worker.dispatchPendingUpdates();

        // 미리 읽어 두면 대기 동안 바뀐 문서를 놓친다 — 순서가 이 흐름의 핵심 불변식이다.
        InOrder inOrder = inOrder(taskStore, userMemoryService, dispatcher);
        inOrder.verify(taskStore).acquireGuard(eq(USER_ID), anyString(), any());
        inOrder.verify(userMemoryService).find(USER_ID);
        inOrder.verify(dispatcher).dispatch(any());
    }

    @Test
    void 접수_body에_현재_문서와_그_지문을_함께_싣는다() throws Exception {
        UserMemoryUpdatePending pending = pending(RECORD_ID);
        JsonNode currentMemory = objectMapper.readTree("{\"schemaVersion\":\"1.0\"}");
        stubPendingQueue(List.of(pending));
        stubClaimable(pending);
        when(userMemoryService.find(USER_ID)).thenReturn(Optional.of(currentMemory));

        worker.dispatchPendingUpdates();

        ArgumentCaptor<AiUserMemoryUpdateRequest> request =
                ArgumentCaptor.forClass(AiUserMemoryUpdateRequest.class);
        verify(dispatcher).dispatch(request.capture());
        assertThat(request.getValue().userMemory()).isEqualTo(currentMemory);

        ArgumentCaptor<UserMemoryUpdateTask> task = ArgumentCaptor.forClass(UserMemoryUpdateTask.class);
        verify(taskStore).save(eq(request.getValue().taskId()), task.capture(), eq(UserMemoryUpdateWorker.TASK_TTL));
        assertThat(task.getValue().baseMemoryHash())
                .isEqualTo(UserMemoryDigest.of(Optional.of(currentMemory)));
        assertThat(task.getValue().dailyRecordIds()).containsExactly(RECORD_ID);
    }

    @Test
    void 접수_body는_question과_memo를_담고_시각에_record_timezone_offset을_붙인다() {
        UserMemoryUpdatePending pending = pending(RECORD_ID);
        stubPendingQueue(List.of(pending));
        stubClaimable(pending);
        when(userMemoryService.find(USER_ID)).thenReturn(Optional.empty());
        when(timelineEventService.findByDailyRecordId(RECORD_ID)).thenReturn(List.of(event()));

        worker.dispatchPendingUpdates();

        ArgumentCaptor<AiUserMemoryUpdateRequest> request =
                ArgumentCaptor.forClass(AiUserMemoryUpdateRequest.class);
        verify(dispatcher).dispatch(request.capture());
        AiUserMemoryUpdateRequest.DailyTimeline timeline = request.getValue().dailyTimelines().getFirst();
        assertThat(timeline.recordDate()).isEqualTo(LocalDate.of(2026, 8, 5));
        assertThat(timeline.recordTimeZone()).isEqualTo("Asia/Seoul");

        AiUserMemoryUpdateRequest.Event dispatched = timeline.events().getFirst();
        assertThat(dispatched.question()).isEqualTo("점심은 어땠나요?");
        assertThat(dispatched.memo()).isEqualTo("응 좋았어");
        assertThat(dispatched.startAt()).isEqualTo(OffsetDateTime.parse("2026-08-05T12:10:00+09:00"));
        assertThat(dispatched.endAt()).isEqualTo(OffsetDateTime.parse("2026-08-05T13:00:00+09:00"));
    }

    @Test
    void 배치는_한_사용자의_밀린_날들을_한_요청으로_묶는다() {
        UserMemoryUpdatePending first = pending(RECORD_ID);
        UserMemoryUpdatePending second = pending(RECORD_ID + 1);
        stubPendingQueue(List.of(first, second));
        when(taskStore.acquireGuard(eq(USER_ID), anyString(), any())).thenReturn(true);
        stubRecord(RECORD_ID);
        stubRecord(RECORD_ID + 1);
        when(userMemoryService.find(USER_ID)).thenReturn(Optional.empty());

        worker.dispatchPendingUpdates();

        // 나눠 보내면 guard가 사용자당 하나라 하루에 한 건씩만 처리된다.
        ArgumentCaptor<AiUserMemoryUpdateRequest> request =
                ArgumentCaptor.forClass(AiUserMemoryUpdateRequest.class);
        verify(dispatcher).dispatch(request.capture());
        assertThat(request.getValue().dailyTimelines()).hasSize(2);
    }

    @Test
    void 배치는_한_요청에_최대_5일까지만_싣는다() {
        List<UserMemoryUpdatePending> pendings = IntStream.range(0, 10)
                .mapToObj(index -> pending(RECORD_ID + index))
                .toList();
        stubPendingQueue(pendings);
        when(taskStore.acquireGuard(eq(USER_ID), anyString(), any())).thenReturn(true);
        // 상한 초과분은 조회조차 하지 않는다 — 여기 stub을 더 두면 Mockito가 미사용으로 잡는다.
        pendings.stream().limit(UserMemoryUpdateWorker.MAX_DAILY_TIMELINES)
                .forEach(pending -> stubRecord(pending.dailyRecordId()));
        when(userMemoryService.find(USER_ID)).thenReturn(Optional.empty());

        worker.dispatchPendingUpdates();

        ArgumentCaptor<AiUserMemoryUpdateRequest> request =
                ArgumentCaptor.forClass(AiUserMemoryUpdateRequest.class);
        verify(dispatcher).dispatch(request.capture());
        assertThat(request.getValue().dailyTimelines()).hasSize(UserMemoryUpdateWorker.MAX_DAILY_TIMELINES);
    }

    @Test
    void 배치는_밀린_사용자를_건수_상한_없이_전부_접수한다() {
        // 제거한 상한이 100건이었다 — 그 아래로 잡으면 옛 코드로도 통과해 회귀를 못 잡는다.
        int users = 150;
        List<UserMemoryUpdatePending> pendings = IntStream.range(0, users)
                .mapToObj(index -> new UserMemoryUpdatePending(USER_ID + index, RECORD_ID + index))
                .toList();
        stubPendingQueue(pendings);
        when(taskStore.acquireGuard(anyLong(), anyString(), any())).thenReturn(true);
        pendings.forEach(pending -> {
            when(dailyRecordService.findByDailyRecordIdAndUserId(pending.dailyRecordId(), pending.userId()))
                    .thenReturn(Optional.of(record(pending.dailyRecordId())));
            when(userMemoryService.find(pending.userId())).thenReturn(Optional.empty());
        });

        worker.dispatchPendingUpdates();

        // 사용자당 접수가 한 건이라 건수 상한은 잘려 나간 사용자를 하루 더 기다리게 할 뿐이다.
        verify(dispatcher, times(users)).dispatch(any());
    }

    @Test
    void 배치는_접수만_하고_큐를_비우지_않는다() {
        UserMemoryUpdatePending pending = pending(RECORD_ID);
        stubPendingQueue(List.of(pending));
        stubClaimable(pending);
        when(userMemoryService.find(USER_ID)).thenReturn(Optional.empty());

        worker.dispatchPendingUpdates();

        // 성패는 접수 시점에 알 수 없다 — 결과 endpoint가 반영을 확인하고 정리한다.
        verify(dispatcher).dispatch(any());
        verify(pendingStore, never()).removeAll(anyLong(), anyList());
    }

    @Test
    void guard를_못_잡으면_접수하지_않고_큐를_그대로_둔다() {
        UserMemoryUpdatePending pending = pending(RECORD_ID);
        stubPendingQueue(List.of(pending));
        when(taskStore.acquireGuard(eq(USER_ID), anyString(), any())).thenReturn(false);

        worker.dispatchPendingUpdates();

        // 앞선 실행의 접수가 아직 진행 중이다 — 장애가 아니라 정상 직렬화라 다음 실행이 가져간다.
        verify(pendingStore, never()).removeAll(anyLong(), anyList());
        verifyNoInteractions(dispatcher, userMemoryService);
    }

    @Test
    void 접수가_4xx로_거절돼도_task와_guard만_정리하고_큐에는_남긴다() {
        UserMemoryUpdatePending pending = pending(RECORD_ID);
        stubPendingQueue(List.of(pending));
        stubClaimable(pending);
        when(userMemoryService.find(USER_ID)).thenReturn(Optional.empty());
        doThrow(new TimelineAiDispatchRejectedException("rejected", new RuntimeException()))
                .when(dispatcher).dispatch(any());

        worker.dispatchPendingUpdates();

        verify(taskStore).delete(anyString());
        verify(taskStore).releaseGuard(USER_ID);
        // 계약 불일치처럼 우리 쪽 수정으로 풀리는 4xx가 있다 — 걷어내면 고친 뒤에도 그 날은 복구되지 않는다.
        verify(pendingStore, never()).removeAll(anyLong(), anyList());
    }

    @Test
    void 접수_결과가_불명이면_task와_guard를_남기고_큐에도_남긴다() {
        UserMemoryUpdatePending pending = pending(RECORD_ID);
        stubPendingQueue(List.of(pending));
        stubClaimable(pending);
        when(userMemoryService.find(USER_ID)).thenReturn(Optional.empty());
        doThrow(new RuntimeException("read timeout")).when(dispatcher).dispatch(any());

        worker.dispatchPendingUpdates();

        // AI가 이미 받아 처리 중일 수 있다 — 지우면 뒤늦게 온 결과가 404로 버려진다.
        verify(taskStore, never()).delete(anyString());
        verify(taskStore, never()).releaseGuard(USER_ID);
        verify(pendingStore, never()).removeAll(anyLong(), anyList());
    }

    @Test
    void 하루_기록이_전부_사라졌으면_접수하지_않고_큐에서_걷어낸다() {
        UserMemoryUpdatePending pending = pending(RECORD_ID);
        stubPendingQueue(List.of(pending));
        when(taskStore.acquireGuard(eq(USER_ID), anyString(), any())).thenReturn(true);
        when(dailyRecordService.findByDailyRecordIdAndUserId(RECORD_ID, USER_ID)).thenReturn(Optional.empty());

        worker.dispatchPendingUpdates();

        // 갱신할 재료가 없으므로 다시 시도할 이유도 없다 — 유일하게 큐에서 걷어내는 경우다.
        verify(pendingStore).removeAll(USER_ID, List.of(RECORD_ID));
        verify(taskStore).releaseGuard(USER_ID);
        verifyNoInteractions(dispatcher);
    }

    @Test
    void 일부_하루_기록만_사라지면_그것만_걷어내고_나머지는_접수한다() {
        UserMemoryUpdatePending alive = pending(RECORD_ID);
        UserMemoryUpdatePending deleted = pending(RECORD_ID + 1);
        stubPendingQueue(List.of(alive, deleted));
        when(taskStore.acquireGuard(eq(USER_ID), anyString(), any())).thenReturn(true);
        stubRecord(RECORD_ID);
        when(dailyRecordService.findByDailyRecordIdAndUserId(RECORD_ID + 1, USER_ID)).thenReturn(Optional.empty());
        when(userMemoryService.find(USER_ID)).thenReturn(Optional.empty());

        worker.dispatchPendingUpdates();

        // 사라진 날은 접수 body에서 빠지므로 결과 endpoint가 지울 근거를 잃는다 — 여기서 안 걷어내면
        // retention까지 큐에 남아 매 실행 사용자당 상한을 갉아먹는다.
        verify(pendingStore).removeAll(USER_ID, List.of(RECORD_ID + 1));
        ArgumentCaptor<AiUserMemoryUpdateRequest> request =
                ArgumentCaptor.forClass(AiUserMemoryUpdateRequest.class);
        verify(dispatcher).dispatch(request.capture());
        assertThat(request.getValue().dailyTimelines()).hasSize(1);
    }

    @Test
    void 배치에서_한_사용자가_실패해도_나머지_사용자를_계속_처리한다() {
        UserMemoryUpdatePending failing = pending(RECORD_ID);
        UserMemoryUpdatePending healthy =
                new UserMemoryUpdatePending(USER_ID + 1, RECORD_ID + 1);
        stubPendingQueue(List.of(failing, healthy));
        when(taskStore.acquireGuard(eq(USER_ID), anyString(), any()))
                .thenThrow(new RuntimeException("redis down"));
        when(taskStore.acquireGuard(eq(USER_ID + 1), anyString(), any())).thenReturn(false);

        worker.dispatchPendingUpdates();

        verify(taskStore).acquireGuard(eq(USER_ID + 1), anyString(), any());
    }

    @Test
    void 대기_항목이_없으면_아무것도_하지_않는다() {
        stubPendingQueue(List.of());

        worker.dispatchPendingUpdates();

        verifyNoInteractions(dispatcher, taskStore, userMemoryService, dailyRecordService);
    }

    private void stubPendingQueue(List<UserMemoryUpdatePending> pending) {
        when(pendingStore.findPending(NOW, UserMemoryUpdateWorker.SCAN_LIMIT))
                .thenReturn(new UserMemoryUpdatePendingStore.PendingScan(pending.size(), pending));
    }

    private void stubClaimable(UserMemoryUpdatePending pending) {
        when(taskStore.acquireGuard(eq(USER_ID), anyString(), any())).thenReturn(true);
        stubRecord(pending.dailyRecordId());
    }

    private void stubRecord(long dailyRecordId) {
        when(dailyRecordService.findByDailyRecordIdAndUserId(dailyRecordId, USER_ID))
                .thenReturn(Optional.of(record(dailyRecordId)));
    }

    private static UserMemoryUpdatePending pending(long dailyRecordId) {
        return new UserMemoryUpdatePending(USER_ID, dailyRecordId);
    }

    private static DailyRecord record(long dailyRecordId) {
        DailyRecord record = DailyRecord.createDraft(
                USER_ID, LocalDate.of(2026, 8, 5), LocalDateTime.of(2026, 8, 5, 21, 0), "Asia/Seoul");
        ReflectionTestUtils.setField(record, "dailyRecordId", dailyRecordId);
        return record;
    }

    private static TimelineEvent event() {
        TimelineEvent event = TimelineEvent.of(RECORD_ID, TimelineEventType.MEAL,
                LocalDateTime.of(2026, 8, 5, 12, 10), LocalDateTime.of(2026, 8, 5, 13, 0),
                "점심", "회사 근처", "점심은 어땠나요?");
        event.updateMemo("응 좋았어");
        return event;
    }
}
