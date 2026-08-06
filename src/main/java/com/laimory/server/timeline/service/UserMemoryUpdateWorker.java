package com.laimory.server.timeline.service;

import com.laimory.server.timeline.TaskTokens;
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
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * User Memory 갱신 접수를 담당한다. 진입점이 둘이고 역할이 다르다.
 *
 * <p><b>1. 저장 직후 즉시 접수({@link #dispatchNow})</b> — 경합이 없는 대부분의 저장이 여기서 끝난다.
 * 요청 스레드가 아니라 async 스레드에서 실행되므로 저장 응답은 기다리지 않는다.
 *
 * <p><b>2. 하루 1회 재시도 배치({@link #retryPendingUpdates})</b> — 1에서 사용자 guard를 못 잡아 대기 큐에
 * 남은 항목과, 프로세스가 죽어 즉시 접수가 유실된 항목을 줍는다. 폴링을 hot path에 두지 않으려는
 * 선택이다 — 경합은 같은 사용자가 짧은 간격으로 두 날짜를 저장할 때만 생기는 드문 경우인데, 그것 때문에
 * 모든 저장이 주기를 기다릴 이유가 없다.
 *
 * <p><b>왜 guard인가</b>: 사용자 하나의 User Memory는 문서 전체를 다시 쓰는 갱신이라, 다른 날짜의 갱신이
 * 진행 중일 때 같이 시작하면 둘 다 같은 base 문서를 읽고 나중에 도착한 쪽이 이겨 하루치가 통째로 사라진다.
 * guard 점유는 장애가 아니라 정상 직렬화이므로 스킵이 아니라 대기로 다룬다.
 *
 * <p><b>불변식(가장 중요)</b>: 접수 body와 {@code baseMemoryHash}는 <b>guard를 잡은 뒤</b> 그 시점의
 * 상태를 읽어 만든다. 대기 중에 앞선 날짜의 갱신이 문서를 바꾸므로, 대기 항목에 미리 조립해 두면 낡은
 * 문서를 base로 삼게 되고 대기 자체가 무의미해진다.
 *
 * <p>배치는 한 사용자의 밀린 날들을 <b>한 요청으로 묶는다</b>. guard가 사용자당 하나라 나눠 보내면 하루에
 * 한 건씩만 처리돼 N일이 밀리면 N일이 걸린다. {@code diaries[]}가 배열이고 AI가 최대 7건을 받는 것이
 * 이 자리다.
 *
 * <p>접수 실패는 어느 경로도 재시도하지 않는다 — 사용자의 저장은 이미 커밋됐고 그 날치 memory 반영만
 * 누락된다. 그래서 AI를 두들기는 루프가 없고 circuit breaker도 두지 않는다.
 *
 * <p>로그에 memory 문서 내용·memo 본문·PII를 남기지 않는다. {@code taskToken} 원문은 어떤 로그에도
 * 남기지 않는다.
 */
@Slf4j
@Component
public class UserMemoryUpdateWorker {

    /** AI 내부 예산 120초에 여유를 둔 값(draft task와 동일). */
    static final Duration TASK_TTL = Duration.ofMinutes(3);

    /** 한 요청에 실을 수 있는 하루 수(AI 계약 상한). 초과분은 다음 배치가 가져간다. */
    static final int MAX_DIARIES = 7;

    private final UserMemoryUpdatePendingStore pendingStore;
    private final UserMemoryUpdateTaskStore taskStore;
    private final DailyRecordService dailyRecordService;
    private final TimelineEventService timelineEventService;
    private final UserMemoryService userMemoryService;
    private final UserMemoryUpdateDispatcher dispatcher;
    private final Clock clock;
    private final int batchSize;

    // batch 크기 프로퍼티 주입이 있어 @RequiredArgsConstructor 대신 명시적 생성자를 쓴다.
    public UserMemoryUpdateWorker(
            UserMemoryUpdatePendingStore pendingStore,
            UserMemoryUpdateTaskStore taskStore,
            DailyRecordService dailyRecordService,
            TimelineEventService timelineEventService,
            UserMemoryService userMemoryService,
            UserMemoryUpdateDispatcher dispatcher,
            Clock clock,
            @Value("${app.user-memory.update.batch-size:500}") int batchSize) {
        this.pendingStore = pendingStore;
        this.taskStore = taskStore;
        this.dailyRecordService = dailyRecordService;
        this.timelineEventService = timelineEventService;
        this.userMemoryService = userMemoryService;
        this.dispatcher = dispatcher;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    /**
     * 저장 커밋 직후의 즉시 접수. guard를 못 잡으면 <b>아무것도 하지 않는다</b> — 대기 항목은 큐에 그대로
     * 남고 하루 1회 배치가 가져간다. 여기서 대기·재시도하면 async 스레드를 붙잡게 된다.
     *
     * <p>실패를 밖으로 던지지 않는다. 호출부(저장 API)는 이미 응답을 냈고 되돌릴 것이 없다.
     */
    @Async
    public void dispatchNow(UserMemoryUpdatePending pending) {
        try {
            process(pending.userId(), List.of(pending), clock.instant());
        } catch (RuntimeException e) {
            log.error("User Memory 갱신 즉시 접수 실패(대기 큐에 남김): userId={} dailyRecordId={}",
                    pending.userId(), pending.dailyRecordId(), e);
        }
    }

    /**
     * 즉시 접수가 guard 경합으로 넘어가지 못했거나 프로세스 종료로 유실된 항목을 하루 1회 줍는다.
     * 사용자별로 묶어 한 요청에 최대 {@link #MAX_DIARIES}일을 싣는다.
     */
    @Scheduled(cron = "${app.user-memory.update.retry-cron:0 30 4 * * *}")
    public void retryPendingUpdates() {
        Instant now = clock.instant();
        Map<Long, List<UserMemoryUpdatePending>> byUser = livePendingByUser(now);
        if (byUser.isEmpty()) {
            return;
        }
        int dispatched = 0;
        for (Map.Entry<Long, List<UserMemoryUpdatePending>> entry : byUser.entrySet()) {
            try {
                dispatched += process(entry.getKey(), entry.getValue(), now) ? 1 : 0;
            } catch (RuntimeException e) {
                // 한 사용자의 실패가 나머지 드레인을 막지 않는다.
                log.error("User Memory 갱신 재시도 실패: userId={} pendingDays={}",
                        entry.getKey(), entry.getValue().size(), e);
            }
        }
        log.info("User Memory 갱신 재시도 배치 완료: users={} dispatched={}", byUser.size(), dispatched);
    }

    /**
     * 대기 큐에서 아직 유효한 항목만 사용자별로 모은다(오래된 순 유지). deadline을 넘긴 항목은 여기서
     * 버린다 — 그동안 계속 막혔다는 뜻이고 저장은 이미 끝났으므로 그 날치 memory 반영만 포기한다.
     */
    private Map<Long, List<UserMemoryUpdatePending>> livePendingByUser(Instant now) {
        return pendingStore.findReady(now, batchSize).stream()
                .filter(pending -> {
                    if (!pending.isExpired(now)) {
                        return true;
                    }
                    pendingStore.remove(pending);
                    log.warn("User Memory 갱신 포기(deadline 초과): userId={} dailyRecordId={}",
                            pending.userId(), pending.dailyRecordId());
                    return false;
                })
                .collect(Collectors.groupingBy(UserMemoryUpdatePending::userId,
                        LinkedHashMap::new, Collectors.toList()));
    }

    /**
     * 사용자 guard를 잡고 그 사용자의 대기분을 한 요청으로 접수한다.
     *
     * @return guard를 잡아 접수까지 갔으면 {@code true}. {@code false}는 그 사용자의 다른 갱신이 진행
     *         중이라는 뜻이고, 대기 항목은 큐에 그대로 남아 다음 배치가 가져간다(실패가 아니다).
     */
    private boolean process(long userId, List<UserMemoryUpdatePending> pendings, Instant now) {
        List<UserMemoryUpdatePending> batch = pendings.stream().limit(MAX_DIARIES).toList();
        String taskId = UUID.randomUUID().toString();
        if (!pendingStore.claim(batch.getFirst(), taskId, TASK_TTL)) {
            return false;
        }
        // claim은 첫 항목만 큐에서 빼므로 같은 요청에 실을 나머지도 함께 정리한다.
        batch.stream().skip(1).forEach(pendingStore::remove);

        try {
            dispatch(userId, batch, taskId, now);
        } catch (TimelineAiDispatchRejectedException e) {
            // 4xx = 미접수 확정. 결과가 올 일이 없으므로 task를 남기지 않는다.
            taskStore.delete(taskId);
            pendingStore.releaseGuard(userId);
            log.error("User Memory 갱신 접수 거절(재시도 없음): userId={} days={} taskId={}",
                    userId, batch.size(), taskId, e);
        } catch (RuntimeException e) {
            // UNKNOWN — AI가 이미 받았을 수 있으므로 task와 guard를 남긴다(결과 도착 또는 TTL이 종결).
            log.error("User Memory 갱신 접수 결과 불명(task 유지): userId={} days={} taskId={}",
                    userId, batch.size(), taskId, e);
        }
        return true;
    }

    /** guard를 잡은 뒤에만 호출된다 — 여기서 읽는 record·event·memory가 접수 body의 권위다. */
    private void dispatch(long userId, List<UserMemoryUpdatePending> batch, String taskId, Instant now) {
        List<DailyRecord> records = batch.stream()
                .map(pending -> dailyRecordService
                        .findByDailyRecordIdAndUserId(pending.dailyRecordId(), userId).orElse(null))
                .filter(java.util.Objects::nonNull)
                .toList();
        if (records.isEmpty()) {
            // 저장 후 사용자가 하루 기록을 지웠다 — 갱신할 재료가 없다.
            taskStore.delete(taskId);
            pendingStore.releaseGuard(userId);
            log.info("User Memory 갱신 취소(하루 기록 없음): userId={} days={}", userId, batch.size());
            return;
        }

        String taskToken = TaskTokens.generate();
        var baseMemory = userMemoryService.find(userId);
        taskStore.save(taskId, new UserMemoryUpdateTask(userId,
                records.stream().map(DailyRecord::getDailyRecordId).toList(),
                TaskTokens.hash(taskToken), now, UserMemoryDigest.of(baseMemory)), TASK_TTL);

        dispatcher.dispatch(new AiUserMemoryUpdateRequest(taskId, taskToken, baseMemory.orElse(null),
                records.stream().map(this::toDailyTimeline).toList()));
        log.info("User Memory 갱신 접수 요청: userId={} days={} taskId={}", userId, records.size(), taskId);
    }

    /**
     * 확정된 타임라인을 접수 body의 하루로 조립한다. 행 PK와 item(사진 등)은 싣지 않고, 시각에는
     * record timezone offset을 붙인다(입력 조회 응답과 같은 규칙).
     */
    private AiUserMemoryUpdateRequest.DailyTimeline toDailyTimeline(DailyRecord record) {
        ZoneId recordZone = ZoneId.of(record.getRecordTimezone());
        List<AiUserMemoryUpdateRequest.Event> events =
                timelineEventService.findByDailyRecordId(record.getDailyRecordId()).stream()
                        .map(event -> toEvent(event, recordZone))
                        .toList();
        return new AiUserMemoryUpdateRequest.DailyTimeline(record.getRecordDate(),
                record.getRecordTimezone(), record.getEmotionType(), events);
    }

    private static AiUserMemoryUpdateRequest.Event toEvent(TimelineEvent event, ZoneId recordZone) {
        return new AiUserMemoryUpdateRequest.Event(
                event.getEventType(), event.getTitle(), event.getSubtitle(), event.getQuestion(),
                toOffset(event.getStartAt(), recordZone), toOffset(event.getEndAt(), recordZone),
                event.getMemo());
    }

    private static OffsetDateTime toOffset(LocalDateTime value, ZoneId recordZone) {
        return value == null ? null : value.atZone(recordZone).toOffsetDateTime();
    }
}
