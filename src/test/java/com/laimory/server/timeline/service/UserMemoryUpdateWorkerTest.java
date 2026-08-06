package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
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
 * <p>나머지는 진입점 둘의 역할 분담이다: 즉시 접수는 guard를 못 잡으면 <b>큐를 건드리지 않고</b> 물러나고,
 * 하루 1회 배치는 남은 항목을 사용자별로 묶어 한 요청으로 보낸다(나눠 보내면 guard가 사용자당 하나라
 * N일이 N일 걸린다).
 */
@ExtendWith(MockitoExtension.class)
class UserMemoryUpdateWorkerTest {

    private static final long USER_ID = 7L;
    private static final long RECORD_ID = 42L;
    private static final Instant NOW = Instant.parse("2026-08-05T12:00:00Z");
    private static final int BATCH_SIZE = 500;

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
                userMemoryService, dispatcher, Clock.fixed(NOW, ZoneOffset.UTC), BATCH_SIZE);
    }

    // ── 즉시 접수 ──

    @Test
    void 즉시_접수는_guard를_못_잡으면_큐를_건드리지_않고_물러난다() {
        UserMemoryUpdatePending pending = pending(RECORD_ID);
        when(pendingStore.claim(eq(pending), anyString(), any())).thenReturn(false);

        worker.dispatchNow(pending);

        // 항목이 큐에 남아야 하루 1회 배치가 줍는다.
        verify(pendingStore, never()).remove(any());
        verifyNoInteractions(dispatcher, taskStore, userMemoryService);
    }

    @Test
    void 조립은_guard를_잡은_뒤에_그_시점의_User_Memory를_읽는다() throws Exception {
        UserMemoryUpdatePending pending = pending(RECORD_ID);
        JsonNode currentMemory = objectMapper.readTree("{\"schemaVersion\":\"1.0\"}");
        stubClaimable(pending);
        when(userMemoryService.find(USER_ID)).thenReturn(Optional.of(currentMemory));

        worker.dispatchNow(pending);

        // 미리 읽어 두면 대기 동안 바뀐 문서를 놓친다 — 순서가 이 흐름의 핵심 불변식이다.
        InOrder inOrder = inOrder(pendingStore, userMemoryService, dispatcher);
        inOrder.verify(pendingStore).claim(eq(pending), anyString(), any());
        inOrder.verify(userMemoryService).find(USER_ID);
        inOrder.verify(dispatcher).dispatch(any());
    }

    @Test
    void 접수_body에_현재_문서와_그_지문을_함께_싣는다() throws Exception {
        UserMemoryUpdatePending pending = pending(RECORD_ID);
        JsonNode currentMemory = objectMapper.readTree("{\"schemaVersion\":\"1.0\"}");
        stubClaimable(pending);
        when(userMemoryService.find(USER_ID)).thenReturn(Optional.of(currentMemory));

        worker.dispatchNow(pending);

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
        stubClaimable(pending);
        when(userMemoryService.find(USER_ID)).thenReturn(Optional.empty());
        when(timelineEventService.findByDailyRecordId(RECORD_ID)).thenReturn(List.of(event()));

        worker.dispatchNow(pending);

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
    void 하루_기록이_사라졌으면_접수하지_않고_guard를_반납한다() {
        UserMemoryUpdatePending pending = pending(RECORD_ID);
        when(pendingStore.claim(eq(pending), anyString(), any())).thenReturn(true);
        when(dailyRecordService.findByDailyRecordIdAndUserId(RECORD_ID, USER_ID)).thenReturn(Optional.empty());

        worker.dispatchNow(pending);

        verify(taskStore).delete(anyString());
        verify(pendingStore).releaseGuard(USER_ID);
        verifyNoInteractions(dispatcher);
    }

    @Test
    void 접수가_4xx로_거절되면_task와_guard를_정리하고_재시도하지_않는다() {
        UserMemoryUpdatePending pending = pending(RECORD_ID);
        stubClaimable(pending);
        when(userMemoryService.find(USER_ID)).thenReturn(Optional.empty());
        doThrow(new TimelineAiDispatchRejectedException("rejected", new RuntimeException()))
                .when(dispatcher).dispatch(any());

        worker.dispatchNow(pending);

        verify(taskStore).delete(anyString());
        verify(pendingStore).releaseGuard(USER_ID);
        verify(dispatcher).dispatch(any());
    }

    @Test
    void 접수_결과가_불명이면_task와_guard를_남긴다() {
        UserMemoryUpdatePending pending = pending(RECORD_ID);
        stubClaimable(pending);
        when(userMemoryService.find(USER_ID)).thenReturn(Optional.empty());
        doThrow(new RuntimeException("read timeout")).when(dispatcher).dispatch(any());

        worker.dispatchNow(pending);

        // AI가 이미 받아 처리 중일 수 있다 — 지우면 뒤늦게 온 결과가 404로 버려진다.
        verify(taskStore, never()).delete(anyString());
        verify(pendingStore, never()).releaseGuard(USER_ID);
    }

    @Test
    void 즉시_접수는_예외를_밖으로_던지지_않는다() {
        UserMemoryUpdatePending pending = pending(RECORD_ID);
        when(pendingStore.claim(eq(pending), anyString(), any())).thenThrow(new RuntimeException("redis down"));

        worker.dispatchNow(pending); // 저장 응답은 이미 나갔고 되돌릴 것이 없다.

        verifyNoInteractions(dispatcher);
    }

    // ── 하루 1회 재시도 배치 ──

    @Test
    void 배치는_한_사용자의_밀린_날들을_한_요청으로_묶는다() {
        UserMemoryUpdatePending first = pending(RECORD_ID);
        UserMemoryUpdatePending second = pending(RECORD_ID + 1);
        when(pendingStore.findReady(NOW, BATCH_SIZE)).thenReturn(List.of(first, second));
        when(pendingStore.claim(eq(first), anyString(), any())).thenReturn(true);
        stubRecord(RECORD_ID);
        stubRecord(RECORD_ID + 1);
        when(userMemoryService.find(USER_ID)).thenReturn(Optional.empty());

        worker.retryPendingUpdates();

        // 나눠 보내면 guard가 사용자당 하나라 하루에 한 건씩만 처리된다.
        ArgumentCaptor<AiUserMemoryUpdateRequest> request =
                ArgumentCaptor.forClass(AiUserMemoryUpdateRequest.class);
        verify(dispatcher).dispatch(request.capture());
        assertThat(request.getValue().dailyTimelines()).hasSize(2);
        // claim이 첫 항목만 빼므로 나머지는 명시적으로 정리한다.
        verify(pendingStore).remove(second);
        verify(pendingStore, never()).remove(first);
    }

    @Test
    void 배치는_한_요청에_최대_7일까지만_싣는다() {
        List<UserMemoryUpdatePending> pendings = IntStream.range(0, 10)
                .mapToObj(index -> pending(RECORD_ID + index))
                .toList();
        when(pendingStore.findReady(NOW, BATCH_SIZE)).thenReturn(pendings);
        when(pendingStore.claim(eq(pendings.getFirst()), anyString(), any())).thenReturn(true);
        // 상한 초과분은 조회조차 하지 않는다 — 여기 stub을 더 두면 Mockito가 미사용으로 잡는다.
        pendings.stream().limit(UserMemoryUpdateWorker.MAX_DIARIES)
                .forEach(pending -> stubRecord(pending.dailyRecordId()));
        when(userMemoryService.find(USER_ID)).thenReturn(Optional.empty());

        worker.retryPendingUpdates();

        ArgumentCaptor<AiUserMemoryUpdateRequest> request =
                ArgumentCaptor.forClass(AiUserMemoryUpdateRequest.class);
        verify(dispatcher).dispatch(request.capture());
        assertThat(request.getValue().dailyTimelines()).hasSize(UserMemoryUpdateWorker.MAX_DIARIES);
        // 초과분은 큐에 남아 다음 배치가 가져간다.
        verify(pendingStore, never()).remove(pendings.get(UserMemoryUpdateWorker.MAX_DIARIES));
    }

    @Test
    void 배치는_deadline을_넘긴_항목을_접수하지_않고_버린다() {
        UserMemoryUpdatePending expired =
                new UserMemoryUpdatePending(USER_ID, RECORD_ID, NOW.minusSeconds(1));
        when(pendingStore.findReady(NOW, BATCH_SIZE)).thenReturn(List.of(expired));

        worker.retryPendingUpdates();

        verify(pendingStore).remove(expired);
        verify(pendingStore, never()).claim(any(), anyString(), any());
        verifyNoInteractions(dispatcher, taskStore);
    }

    @Test
    void 배치에서_한_사용자가_실패해도_나머지_사용자를_계속_처리한다() {
        UserMemoryUpdatePending failing = pending(RECORD_ID);
        UserMemoryUpdatePending healthy =
                new UserMemoryUpdatePending(USER_ID + 1, RECORD_ID + 1, NOW.plusSeconds(300));
        when(pendingStore.findReady(NOW, BATCH_SIZE)).thenReturn(List.of(failing, healthy));
        when(pendingStore.claim(eq(failing), anyString(), any()))
                .thenThrow(new RuntimeException("redis down"));
        when(pendingStore.claim(eq(healthy), anyString(), any())).thenReturn(false);

        worker.retryPendingUpdates();

        verify(pendingStore).claim(eq(healthy), anyString(), any());
    }

    @Test
    void 대기_항목이_없으면_아무것도_하지_않는다() {
        when(pendingStore.findReady(eq(NOW), anyInt())).thenReturn(List.of());

        worker.retryPendingUpdates();

        verifyNoInteractions(dispatcher, taskStore, userMemoryService, dailyRecordService);
    }

    private void stubClaimable(UserMemoryUpdatePending pending) {
        when(pendingStore.claim(eq(pending), anyString(), any())).thenReturn(true);
        stubRecord(pending.dailyRecordId());
    }

    private void stubRecord(long dailyRecordId) {
        when(dailyRecordService.findByDailyRecordIdAndUserId(dailyRecordId, USER_ID))
                .thenReturn(Optional.of(record(dailyRecordId)));
    }

    private static UserMemoryUpdatePending pending(long dailyRecordId) {
        return new UserMemoryUpdatePending(USER_ID, dailyRecordId, NOW.plusSeconds(300));
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
