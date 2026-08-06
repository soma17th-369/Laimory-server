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
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * User Memory 갱신 대기 큐를 드레인하는 worker. 사용자 guard를 잡은 항목만 접수로 넘긴다.
 *
 * <p><b>왜 대기인가</b>: 사용자 하나의 User Memory는 문서 전체를 다시 쓰는 갱신이라, 다른 날짜의 갱신이
 * 진행 중일 때 같이 시작하면 둘 다 같은 base 문서를 읽고 나중에 도착한 쪽이 이겨 하루치가 통째로
 * 사라진다. guard 점유는 장애가 아니라 정상 직렬화이므로 스킵이 아니라 대기로 다룬다 — 사용자가
 * 8/4·8/5를 연달아 저장하는 정상 패턴에서 하루를 버리지 않기 위해서다.
 *
 * <p><b>불변식(가장 중요)</b>: 접수 body와 {@code baseMemoryHash}는 <b>guard를 잡은 뒤</b> 그 시점의
 * 상태를 읽어 만든다. 대기 중에 앞선 날짜의 갱신이 문서를 바꾸므로, 등록 시점에 조립해 두면 낡은
 * 문서를 base로 삼게 되고 대기 자체가 무의미해진다.
 *
 * <p>접수 실패는 재시도하지 않는다 — 사용자의 저장은 이미 커밋됐고 그 날치 memory 반영만 누락된다.
 * 그래서 AI를 두들기는 루프가 없고 circuit breaker도 두지 않는다.
 *
 * <p>대기 큐가 Redis에 있어 재배포·재시작을 견디고, 여러 인스턴스가 동시에 드레인해도 안전하다 —
 * 실제 직렬화 판정은 큐가 아니라 guard(SET NX)와 base 지문이 한다.
 *
 * <p>로그에 memory 문서 내용·memo 본문·PII를 남기지 않는다. {@code taskToken} 원문은 어떤 로그에도
 * 남기지 않는다.
 */
@Slf4j
@Component
public class UserMemoryUpdateWorker {

    /** AI 내부 예산 120초에 여유를 둔 값(draft task와 동일). */
    static final Duration TASK_TTL = Duration.ofMinutes(3);

    private final UserMemoryUpdatePendingStore pendingStore;
    private final UserMemoryUpdateTaskStore taskStore;
    private final DailyRecordService dailyRecordService;
    private final TimelineEventService timelineEventService;
    private final UserMemoryService userMemoryService;
    private final UserMemoryUpdateDispatcher dispatcher;
    private final Clock clock;
    private final Duration retryInterval;
    private final int batchSize;

    // 재시도 간격·batch 크기 프로퍼티 주입이 있어 @RequiredArgsConstructor 대신 명시적 생성자를 쓴다.
    public UserMemoryUpdateWorker(
            UserMemoryUpdatePendingStore pendingStore,
            UserMemoryUpdateTaskStore taskStore,
            DailyRecordService dailyRecordService,
            TimelineEventService timelineEventService,
            UserMemoryService userMemoryService,
            UserMemoryUpdateDispatcher dispatcher,
            Clock clock,
            @Value("${app.user-memory.update.retry-interval:15s}") Duration retryInterval,
            @Value("${app.user-memory.update.batch-size:50}") int batchSize) {
        this.pendingStore = pendingStore;
        this.taskStore = taskStore;
        this.dailyRecordService = dailyRecordService;
        this.timelineEventService = timelineEventService;
        this.userMemoryService = userMemoryService;
        this.dispatcher = dispatcher;
        this.clock = clock;
        this.retryInterval = retryInterval;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${app.user-memory.update.fixed-delay:15s}")
    public void dispatchPendingUpdates() {
        Instant now = clock.instant();
        for (UserMemoryUpdatePending pending : pendingStore.findReady(now, batchSize)) {
            try {
                process(pending, now);
            } catch (RuntimeException e) {
                // 한 항목의 실패가 나머지 드레인을 막지 않는다.
                log.error("User Memory 갱신 처리 실패: userId={} dailyRecordId={}",
                        pending.userId(), pending.dailyRecordId(), e);
            }
        }
    }

    private void process(UserMemoryUpdatePending pending, Instant now) {
        if (pending.isExpired(now)) {
            // 앞이 deadline 내내 막혔다는 뜻이다. 저장은 이미 끝났으므로 그 날치 memory 반영만 포기한다.
            pendingStore.remove(pending);
            log.warn("User Memory 갱신 포기(deadline 초과): userId={} dailyRecordId={}",
                    pending.userId(), pending.dailyRecordId());
            return;
        }

        String taskId = UUID.randomUUID().toString();
        if (!pendingStore.claim(pending, taskId, TASK_TTL)) {
            // 그 사용자의 다른 날짜가 진행 중 — 실패가 아니라 대기다.
            pendingStore.reschedule(pending, now.plus(retryInterval));
            return;
        }

        try {
            dispatch(pending, taskId, now);
        } catch (TimelineAiDispatchRejectedException e) {
            // 4xx = 미접수 확정. 결과가 올 일이 없으므로 task를 남기지 않는다.
            taskStore.delete(taskId);
            pendingStore.releaseGuard(pending.userId());
            log.error("User Memory 갱신 접수 거절(재시도 없음): userId={} dailyRecordId={} taskId={}",
                    pending.userId(), pending.dailyRecordId(), taskId, e);
        } catch (RuntimeException e) {
            // UNKNOWN — AI가 이미 받았을 수 있으므로 task와 guard를 남긴다(결과 도착 또는 TTL이 종결).
            log.error("User Memory 갱신 접수 결과 불명(task 유지): userId={} dailyRecordId={} taskId={}",
                    pending.userId(), pending.dailyRecordId(), taskId, e);
        }
    }

    /** guard를 잡은 뒤에만 호출된다 — 여기서 읽는 record·event·memory가 접수 body의 권위다. */
    private void dispatch(UserMemoryUpdatePending pending, String taskId, Instant now) {
        DailyRecord record = dailyRecordService
                .findByDailyRecordIdAndUserId(pending.dailyRecordId(), pending.userId())
                .orElse(null);
        if (record == null) {
            // 저장 후 사용자가 하루 기록을 지웠다 — 갱신할 재료가 없다.
            taskStore.delete(taskId);
            pendingStore.releaseGuard(pending.userId());
            log.info("User Memory 갱신 취소(하루 기록 없음): userId={} dailyRecordId={}",
                    pending.userId(), pending.dailyRecordId());
            return;
        }

        String taskToken = TaskTokens.generate();
        var baseMemory = userMemoryService.find(pending.userId());
        taskStore.save(taskId, new UserMemoryUpdateTask(pending.userId(), pending.dailyRecordId(),
                TaskTokens.hash(taskToken), now, UserMemoryDigest.of(baseMemory)), TASK_TTL);

        dispatcher.dispatch(new AiUserMemoryUpdateRequest(taskId, taskToken, baseMemory.orElse(null),
                List.of(toDiary(record))));
        log.info("User Memory 갱신 접수 요청: userId={} dailyRecordId={} taskId={}",
                pending.userId(), pending.dailyRecordId(), taskId);
    }

    /**
     * 확정된 타임라인을 접수 body의 하루로 조립한다. 행 PK와 item(사진 등)은 싣지 않고, 시각에는
     * record timezone offset을 붙인다(입력 조회 응답과 같은 규칙).
     */
    private AiUserMemoryUpdateRequest.Diary toDiary(DailyRecord record) {
        ZoneId recordZone = ZoneId.of(record.getRecordTimezone());
        List<AiUserMemoryUpdateRequest.Event> events =
                timelineEventService.findByDailyRecordId(record.getDailyRecordId()).stream()
                        .map(event -> toEvent(event, recordZone))
                        .toList();
        return new AiUserMemoryUpdateRequest.Diary(record.getRecordDate(), record.getRecordTimezone(),
                record.getEmotionType(), events);
    }

    private static AiUserMemoryUpdateRequest.Event toEvent(TimelineEvent event, ZoneId recordZone) {
        return new AiUserMemoryUpdateRequest.Event(
                event.getEventType(), event.getTitle(), event.getSubtitle(),
                event.getQuestion(), event.getMemo(),
                toOffset(event.getStartAt(), recordZone), toOffset(event.getEndAt(), recordZone));
    }

    private static OffsetDateTime toOffset(LocalDateTime value, ZoneId recordZone) {
        return value == null ? null : value.atZone(recordZone).toOffsetDateTime();
    }
}
