package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.common.privacy.PrivacyRedactor;
import com.laimory.server.common.privacy.RedactionType;
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
 * <p>두 번째는 <b>어느 날을 싣느냐</b>다. 큐 순서는 진입 시각이라 기록 날짜와 다를 수 있는데, 갱신이
 * 접기라 기록 날짜 순서로 접어야 한다. 그래서 상한을 큐 순서가 아니라 {@code record_date} 오름차순
 * 조회 결과에 적용한다(정렬 자체는 조회의 책임이라 여기서는 그 순서를 뒤집지 않는 것만 고정한다).
 *
 * <p>세 번째는 <b>큐를 언제 비우느냐</b>다. 저장된 하루는 예외 없이 큐를 거치고 접수는 배치가 전담하므로,
 * 접수 결과가 무엇이든 큐는 그대로 둔다 — 걷어내는 유일한 경우는 갱신할 재료(하루 기록)가 사라졌을 때이고
 * 일부만 사라진 경우도 같다. 나머지는 결과 endpoint가 반영을 확인하고 정리한다.
 */
@ExtendWith(MockitoExtension.class)
class UserMemoryUpdateWorkerTest {

    private static final long USER_ID = 7L;
    private static final long RECORD_ID = 42L;
    private static final LocalDate RECORD_DATE = LocalDate.of(2026, 8, 5);
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

    // 치환 검증은 실물 redactor로 하고, 실패 주입 테스트만 mock으로 바꿔 끼운다.
    private final PrivacyRedactor privacyRedactor = new PrivacyRedactor();

    private UserMemoryUpdateWorker worker;

    @BeforeEach
    void setUp() {
        worker = new UserMemoryUpdateWorker(pendingStore, taskStore, dailyRecordService, timelineEventService,
                userMemoryService, dispatcher, privacyRedactor, Clock.fixed(NOW, ZoneOffset.UTC));
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
        assertThat(timeline.recordDate()).isEqualTo(RECORD_DATE);
        assertThat(timeline.recordTimeZone()).isEqualTo("Asia/Seoul");

        AiUserMemoryUpdateRequest.Event dispatched = timeline.events().getFirst();
        assertThat(dispatched.question()).isEqualTo("점심은 어땠나요?");
        assertThat(dispatched.memo()).isEqualTo("응 좋았어");
        assertThat(dispatched.startAt()).isEqualTo(OffsetDateTime.parse("2026-08-05T12:10:00+09:00"));
        assertThat(dispatched.endAt()).isEqualTo(OffsetDateTime.parse("2026-08-05T13:00:00+09:00"));
    }

    @Test
    void 접수_body의_base_문서와_Event_text는_치환하고_지문은_DB_원본으로_계산한다() throws Exception {
        UserMemoryUpdatePending pending = pending(RECORD_ID);
        JsonNode originalMemory = objectMapper.readTree("{\"profile\":{\"contact\":\"010-1234-5678\"}}");
        stubPendingQueue(List.of(pending));
        stubClaimable(pending);
        when(userMemoryService.find(USER_ID)).thenReturn(Optional.of(originalMemory));
        when(timelineEventService.findByDailyRecordId(RECORD_ID)).thenReturn(List.of(eventWithPii()));

        worker.dispatchPendingUpdates();

        ArgumentCaptor<AiUserMemoryUpdateRequest> request =
                ArgumentCaptor.forClass(AiUserMemoryUpdateRequest.class);
        verify(dispatcher).dispatch(request.capture());
        // AI body의 base 문서와 Event text는 치환본이다(DB에는 원문이 남는다).
        assertThat(request.getValue().userMemory().at("/profile/contact").textValue())
                .isEqualTo(RedactionType.PHONE.token());
        AiUserMemoryUpdateRequest.Event dispatched =
                request.getValue().dailyTimelines().getFirst().events().getFirst();
        assertThat(dispatched.title()).isEqualTo("전화 " + RedactionType.PHONE.token());
        assertThat(dispatched.memo()).isEqualTo("메일 " + RedactionType.EMAIL.token());

        // baseMemoryHash는 반드시 DB 원본으로 계산한다 — 치환본 기준이면 결과 endpoint의 지문 대조가 깨진다.
        ArgumentCaptor<UserMemoryUpdateTask> task = ArgumentCaptor.forClass(UserMemoryUpdateTask.class);
        verify(taskStore).save(eq(request.getValue().taskId()), task.capture(),
                eq(UserMemoryUpdateWorker.TASK_TTL));
        assertThat(task.getValue().baseMemoryHash())
                .isEqualTo(UserMemoryDigest.of(Optional.of(originalMemory)));
        assertThat(task.getValue().baseMemoryHash())
                .isNotEqualTo(UserMemoryDigest.of(Optional.of(request.getValue().userMemory())));
    }

    @Test
    void redaction이_실패하면_task_저장과_접수_없이_큐를_그대로_둔다() throws Exception {
        // redacted DTO는 task 저장 전에 완성된다 — 실패는 원문 fallback 없이 task 저장·dispatch를 막고
        // pending을 유지해 다음 배치가 다시 시도한다(기존 worker 실패 계약과 동일 수렴).
        UserMemoryUpdatePending pending = pending(RECORD_ID);
        stubPendingQueue(List.of(pending));
        stubClaimable(pending);
        when(userMemoryService.find(USER_ID))
                .thenReturn(Optional.of(objectMapper.readTree("{\"schemaVersion\":\"1.0\"}")));
        PrivacyRedactor failingRedactor = mock(PrivacyRedactor.class);
        when(failingRedactor.redactTree(any(JsonNode.class)))
                .thenThrow(new RuntimeException("redactor down"));
        UserMemoryUpdateWorker failingWorker = new UserMemoryUpdateWorker(pendingStore, taskStore,
                dailyRecordService, timelineEventService, userMemoryService, dispatcher,
                failingRedactor, Clock.fixed(NOW, ZoneOffset.UTC));

        failingWorker.dispatchPendingUpdates();

        verify(taskStore, never()).save(anyString(), any(), any());
        verifyNoInteractions(dispatcher);
        verify(pendingStore, never()).removeAll(anyLong(), anyList());
    }

    @Test
    void 배치는_한_사용자의_밀린_날들을_한_요청으로_묶는다() {
        UserMemoryUpdatePending first = pending(RECORD_ID);
        UserMemoryUpdatePending second = pending(RECORD_ID + 1);
        stubPendingQueue(List.of(first, second));
        when(taskStore.acquireGuard(eq(USER_ID), anyString(), any())).thenReturn(true);
        stubLookup(List.of(first, second), List.of(record(RECORD_ID), record(RECORD_ID + 1)));
        when(userMemoryService.find(USER_ID)).thenReturn(Optional.empty());

        worker.dispatchPendingUpdates();

        // 나눠 보내면 guard가 사용자당 하나라 하루에 한 건씩만 처리된다.
        ArgumentCaptor<AiUserMemoryUpdateRequest> request =
                ArgumentCaptor.forClass(AiUserMemoryUpdateRequest.class);
        verify(dispatcher).dispatch(request.capture());
        assertThat(request.getValue().dailyTimelines()).hasSize(2);
    }

    @Test
    void 상한은_큐_순서가_아니라_기록_날짜_순서의_앞에서_자른다() {
        // 큐 진입 순서는 8/5 → 8/1 → 8/2(과거 날짜를 나중에 저장). 조회는 record_date 오름차순으로 준다.
        UserMemoryUpdatePending late = pending(RECORD_ID);
        UserMemoryUpdatePending backfillFirst = pending(RECORD_ID + 1);
        UserMemoryUpdatePending backfillSecond = pending(RECORD_ID + 2);
        stubPendingQueue(List.of(late, backfillFirst, backfillSecond));
        when(taskStore.acquireGuard(eq(USER_ID), anyString(), any())).thenReturn(true);
        stubLookup(List.of(late, backfillFirst, backfillSecond), List.of(
                record(RECORD_ID + 1, LocalDate.of(2026, 8, 1)),
                record(RECORD_ID + 2, LocalDate.of(2026, 8, 2)),
                record(RECORD_ID, LocalDate.of(2026, 8, 5))));
        when(userMemoryService.find(USER_ID)).thenReturn(Optional.empty());

        worker.dispatchPendingUpdates();

        // 접기는 기록 날짜 순서로 해야 한다 — 조회가 준 순서를 뒤집지 않는다.
        ArgumentCaptor<AiUserMemoryUpdateRequest> request =
                ArgumentCaptor.forClass(AiUserMemoryUpdateRequest.class);
        verify(dispatcher).dispatch(request.capture());
        assertThat(request.getValue().dailyTimelines())
                .extracting(AiUserMemoryUpdateRequest.DailyTimeline::recordDate)
                .containsExactly(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 2), LocalDate.of(2026, 8, 5));
    }

    @Test
    void 배치는_한_요청에_최대_5일까지만_싣는다() {
        List<UserMemoryUpdatePending> pendings = IntStream.range(0, 10)
                .mapToObj(index -> pending(RECORD_ID + index))
                .toList();
        stubPendingQueue(pendings);
        when(taskStore.acquireGuard(eq(USER_ID), anyString(), any())).thenReturn(true);
        // 조회는 밀린 날 전부를 날짜 순으로 돌려주고, 상한은 그 앞에서 잘린다.
        stubLookup(pendings, IntStream.range(0, 10)
                .mapToObj(index -> record(RECORD_ID + index, RECORD_DATE.plusDays(index)))
                .toList());
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
            when(dailyRecordService.findAllByUserIdAndIdsOrderByRecordDate(
                    pending.userId(), List.of(pending.dailyRecordId())))
                    .thenReturn(List.of(record(pending.dailyRecordId())));
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
        verifyNoInteractions(dispatcher, userMemoryService, dailyRecordService);
    }

    @Test
    void 접수가_4xx로_거절되면_task만_지우고_guard도_큐도_그대로_둔다() {
        UserMemoryUpdatePending pending = pending(RECORD_ID);
        stubPendingQueue(List.of(pending));
        stubClaimable(pending);
        when(userMemoryService.find(USER_ID)).thenReturn(Optional.empty());
        doThrow(new TimelineAiDispatchRejectedException("rejected", new RuntimeException()))
                .when(dispatcher).dispatch(any());

        worker.dispatchPendingUpdates();

        verify(taskStore).delete(anyString());
        // guard 반납은 TTL에 맡긴다 — 같은 실행에서 다시 보내 봐야 같은 payload라 또 4xx다.
        verify(taskStore, never()).releaseGuard(anyLong());
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
        verify(taskStore, never()).releaseGuard(anyLong());
        verify(pendingStore, never()).removeAll(anyLong(), anyList());
    }

    @Test
    void 하루_기록이_전부_사라졌으면_접수하지_않고_큐에서_걷어낸다() {
        UserMemoryUpdatePending pending = pending(RECORD_ID);
        stubPendingQueue(List.of(pending));
        when(taskStore.acquireGuard(eq(USER_ID), anyString(), any())).thenReturn(true);
        stubLookup(List.of(pending), List.of());

        worker.dispatchPendingUpdates();

        // 갱신할 재료가 없으므로 다시 시도할 이유도 없다 — 유일하게 큐에서 걷어내는 경우다.
        verify(pendingStore).removeAll(USER_ID, List.of(RECORD_ID));
        // task는 저장 전이고 guard는 TTL이 반납한다 — 그 사용자에게 이번 실행에 할 일이 없다.
        verify(taskStore, never()).releaseGuard(anyLong());
        verifyNoInteractions(dispatcher);
    }

    @Test
    void 일부_하루_기록만_사라지면_그것만_걷어내고_나머지는_접수한다() {
        UserMemoryUpdatePending alive = pending(RECORD_ID);
        UserMemoryUpdatePending deleted = pending(RECORD_ID + 1);
        stubPendingQueue(List.of(alive, deleted));
        when(taskStore.acquireGuard(eq(USER_ID), anyString(), any())).thenReturn(true);
        stubLookup(List.of(alive, deleted), List.of(record(RECORD_ID)));
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
        stubLookup(List.of(pending), List.of(record(pending.dailyRecordId())));
    }

    /** 조회는 밀린 날 전부를 받아 <b>살아 있는 것만</b> record_date 오름차순으로 돌려준다. */
    private void stubLookup(List<UserMemoryUpdatePending> pending, List<DailyRecord> found) {
        when(dailyRecordService.findAllByUserIdAndIdsOrderByRecordDate(USER_ID,
                pending.stream().map(UserMemoryUpdatePending::dailyRecordId).toList()))
                .thenReturn(found);
    }

    private static UserMemoryUpdatePending pending(long dailyRecordId) {
        return new UserMemoryUpdatePending(USER_ID, dailyRecordId);
    }

    private static DailyRecord record(long dailyRecordId) {
        return record(dailyRecordId, RECORD_DATE);
    }

    private static DailyRecord record(long dailyRecordId, LocalDate recordDate) {
        DailyRecord record = DailyRecord.createDraft(
                USER_ID, recordDate, recordDate.atTime(21, 0), "Asia/Seoul");
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

    /** DB에 원문 저장된 Event — AI 전달 DTO에서만 치환돼야 하는 v1 PII fixture를 담는다. */
    private static TimelineEvent eventWithPii() {
        TimelineEvent event = TimelineEvent.of(RECORD_ID, TimelineEventType.MEAL,
                LocalDateTime.of(2026, 8, 5, 12, 10), LocalDateTime.of(2026, 8, 5, 13, 0),
                "전화 010-1234-5678", null, null);
        event.updateMemo("메일 yun@example.com");
        return event;
    }
}
