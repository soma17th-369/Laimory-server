package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 갱신 대기 큐 worker 단위 검증(결정 7).
 *
 * <p>가장 중요한 계약은 <b>조립이 guard 획득 뒤에 일어난다</b>는 것이다 — 대기 중에 앞선 날짜의 갱신이
 * User Memory를 바꾸므로, 미리 읽어 두면 낡은 문서를 base로 삼게 되고 대기가 무의미해진다.
 * 나머지는 guard 점유 시 대기(스킵 아님), deadline 초과 시 포기, 접수 실패 분류다.
 */
@ExtendWith(MockitoExtension.class)
class UserMemoryUpdateWorkerTest {

    private static final long USER_ID = 7L;
    private static final long RECORD_ID = 42L;
    private static final Instant NOW = Instant.parse("2026-08-05T12:00:00Z");
    private static final Duration RETRY_INTERVAL = Duration.ofSeconds(15);
    private static final int BATCH_SIZE = 50;

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
                userMemoryService, dispatcher, Clock.fixed(NOW, ZoneOffset.UTC), RETRY_INTERVAL, BATCH_SIZE);
    }

    @Test
    void guard가_점유돼_있으면_스킵하지_않고_다음_주기로_미룬다() {
        UserMemoryUpdatePending pending = pending(NOW.plusSeconds(300));
        when(pendingStore.findReady(NOW, BATCH_SIZE)).thenReturn(List.of(pending));
        when(pendingStore.claim(eq(pending), anyString(), any())).thenReturn(false);

        worker.dispatchPendingUpdates();

        verify(pendingStore).reschedule(pending, NOW.plus(RETRY_INTERVAL));
        verify(pendingStore, never()).remove(pending);
        verifyNoInteractions(dispatcher, taskStore, userMemoryService);
    }

    @Test
    void 조립은_guard를_잡은_뒤에_그_시점의_User_Memory를_읽는다() throws Exception {
        UserMemoryUpdatePending pending = pending(NOW.plusSeconds(300));
        JsonNode currentMemory = objectMapper.readTree("{\"schemaVersion\":\"1.0\"}");
        stubClaimable(pending);
        when(userMemoryService.find(USER_ID)).thenReturn(Optional.of(currentMemory));

        worker.dispatchPendingUpdates();

        // 미리 읽어 두면 대기 동안 바뀐 문서를 놓친다 — 순서가 이 변경의 핵심 불변식이다.
        InOrder inOrder = inOrder(pendingStore, userMemoryService, dispatcher);
        inOrder.verify(pendingStore).claim(eq(pending), anyString(), any());
        inOrder.verify(userMemoryService).find(USER_ID);
        inOrder.verify(dispatcher).dispatch(any());
    }

    @Test
    void 접수_body에_현재_문서와_그_지문을_함께_싣는다() throws Exception {
        UserMemoryUpdatePending pending = pending(NOW.plusSeconds(300));
        JsonNode currentMemory = objectMapper.readTree("{\"schemaVersion\":\"1.0\"}");
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
    }

    @Test
    void 접수_body는_question과_memo를_담고_시각에_record_timezone_offset을_붙인다() {
        UserMemoryUpdatePending pending = pending(NOW.plusSeconds(300));
        stubClaimable(pending);
        when(userMemoryService.find(USER_ID)).thenReturn(Optional.empty());
        when(timelineEventService.findByDailyRecordId(RECORD_ID)).thenReturn(List.of(event()));

        worker.dispatchPendingUpdates();

        ArgumentCaptor<AiUserMemoryUpdateRequest> request =
                ArgumentCaptor.forClass(AiUserMemoryUpdateRequest.class);
        verify(dispatcher).dispatch(request.capture());
        AiUserMemoryUpdateRequest.Diary diary = request.getValue().diaries().getFirst();
        assertThat(diary.date()).isEqualTo(LocalDate.of(2026, 8, 5));
        assertThat(diary.recordTimeZone()).isEqualTo("Asia/Seoul");

        AiUserMemoryUpdateRequest.Event dispatched = diary.events().getFirst();
        assertThat(dispatched.question()).isEqualTo("점심은 어땠나요?");
        assertThat(dispatched.memo()).isEqualTo("응 좋았어");
        assertThat(dispatched.startAt())
                .isEqualTo(OffsetDateTime.parse("2026-08-05T12:10:00+09:00"));
        assertThat(dispatched.endAt())
                .isEqualTo(OffsetDateTime.parse("2026-08-05T13:00:00+09:00"));
    }

    @Test
    void deadline을_넘긴_항목은_접수하지_않고_버린다() {
        UserMemoryUpdatePending pending = pending(NOW.minusSeconds(1));
        when(pendingStore.findReady(NOW, BATCH_SIZE)).thenReturn(List.of(pending));

        worker.dispatchPendingUpdates();

        verify(pendingStore).remove(pending);
        verify(pendingStore, never()).claim(any(), anyString(), any());
        verifyNoInteractions(dispatcher, taskStore);
    }

    @Test
    void 하루_기록이_사라졌으면_접수하지_않고_guard를_반납한다() {
        UserMemoryUpdatePending pending = pending(NOW.plusSeconds(300));
        when(pendingStore.findReady(NOW, BATCH_SIZE)).thenReturn(List.of(pending));
        when(pendingStore.claim(eq(pending), anyString(), any())).thenReturn(true);
        when(dailyRecordService.findByDailyRecordIdAndUserId(RECORD_ID, USER_ID)).thenReturn(Optional.empty());

        worker.dispatchPendingUpdates();

        verify(taskStore).delete(anyString());
        verify(pendingStore).releaseGuard(USER_ID);
        verifyNoInteractions(dispatcher);
    }

    @Test
    void 접수가_4xx로_거절되면_task와_guard를_정리하고_재시도하지_않는다() {
        UserMemoryUpdatePending pending = pending(NOW.plusSeconds(300));
        stubClaimable(pending);
        when(userMemoryService.find(USER_ID)).thenReturn(Optional.empty());
        doThrow(new TimelineAiDispatchRejectedException("rejected", new RuntimeException()))
                .when(dispatcher).dispatch(any());

        worker.dispatchPendingUpdates();

        verify(taskStore).delete(anyString());
        verify(pendingStore).releaseGuard(USER_ID);
        verify(pendingStore, never()).reschedule(any(), any());
        verify(dispatcher).dispatch(any());
    }

    @Test
    void 접수_결과가_불명이면_task와_guard를_남긴다() {
        UserMemoryUpdatePending pending = pending(NOW.plusSeconds(300));
        stubClaimable(pending);
        when(userMemoryService.find(USER_ID)).thenReturn(Optional.empty());
        doThrow(new RuntimeException("read timeout")).when(dispatcher).dispatch(any());

        worker.dispatchPendingUpdates();

        // AI가 이미 받아 처리 중일 수 있다 — 지우면 뒤늦게 온 결과가 404로 버려진다.
        verify(taskStore, never()).delete(anyString());
        verify(pendingStore, never()).releaseGuard(USER_ID);
        verify(pendingStore, never()).reschedule(any(), any());
    }

    @Test
    void 한_항목이_실패해도_나머지_항목을_계속_처리한다() {
        UserMemoryUpdatePending failing = pending(NOW.plusSeconds(300));
        UserMemoryUpdatePending healthy = new UserMemoryUpdatePending(USER_ID + 1, RECORD_ID + 1,
                NOW.plusSeconds(300));
        when(pendingStore.findReady(NOW, BATCH_SIZE)).thenReturn(List.of(failing, healthy));
        when(pendingStore.claim(eq(failing), anyString(), any()))
                .thenThrow(new RuntimeException("redis down"));
        when(pendingStore.claim(eq(healthy), anyString(), any())).thenReturn(false);

        worker.dispatchPendingUpdates();

        verify(pendingStore).reschedule(healthy, NOW.plus(RETRY_INTERVAL));
    }

    private void stubClaimable(UserMemoryUpdatePending pending) {
        when(pendingStore.findReady(NOW, BATCH_SIZE)).thenReturn(List.of(pending));
        when(pendingStore.claim(eq(pending), anyString(), any())).thenReturn(true);
        when(dailyRecordService.findByDailyRecordIdAndUserId(RECORD_ID, USER_ID))
                .thenReturn(Optional.of(record()));
    }

    private static UserMemoryUpdatePending pending(Instant deadline) {
        return new UserMemoryUpdatePending(USER_ID, RECORD_ID, deadline);
    }

    private static DailyRecord record() {
        DailyRecord record = DailyRecord.createDraft(
                USER_ID, LocalDate.of(2026, 8, 5), LocalDateTime.of(2026, 8, 5, 21, 0), "Asia/Seoul");
        ReflectionTestUtils.setField(record, "dailyRecordId", RECORD_ID);
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
