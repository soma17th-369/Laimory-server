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
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * User Memory 갱신 접수를 담당한다. 진입점이 둘이고 역할이 다르다.
 *
 * <p><b>1. 저장 직후 즉시 접수({@link #dispatchNow})</b> — 접수에 성공하는 대부분의 저장이 여기서 끝난다.
 * async 스레드에서 실행된다. 접수되지 못한 날만 그 작업을 큐에 남긴다.
 *
 * <p><b>2. 하루 1회 재시도 배치({@link #retryPendingUpdates})</b> — 큐에 쌓인 것, 즉 아직 반영되지 않은
 * 작업만 다시 접수한다.
 *
 * <p><b>어느 쪽도 결과를 기다리지 않는다.</b> AI 계약이 "202 접수 → 백그라운드 처리 → 완료 시 결과 API
 * 호출"이라 응답을 기다리는 척하려면 폴링을 얹어야 하고, 그건 프로토콜과 싸우는 짓이다. 반영 확인과 큐
 * 정리는 성패를 실제로 아는 지점, 즉 결과 endpoint({@link UserMemoryUpdateResultService})가 한다 —
 * 반영되면 큐에서 빼고, AI가 실패를 통보하면 큐에 넣는다.
 *
 * <p><b>왜 guard인가</b>: 사용자 하나의 User Memory는 문서 전체를 다시 쓰는 갱신이라, 다른 날짜의 갱신이
 * 진행 중일 때 같이 시작하면 둘 다 같은 base 문서를 읽고 나중에 도착한 쪽이 이겨 하루치가 통째로 사라진다.
 * guard 획득 실패가 곧 <b>"이 사용자의 갱신이 진행 중"</b>이라는 판정이고, 그래서 별도의 진행 상태 저장
 * 없이 guard 하나가 직렬화와 실패 판정을 겸한다. 점유는 장애가 아니라 정상 직렬화라 버리지 않고 미룬다.
 *
 * <p><b>불변식(가장 중요)</b>: 접수 body와 {@code baseMemoryHash}는 <b>guard를 잡은 뒤</b> 그 시점의
 * 상태를 읽어 만든다. 밀려 있는 동안 앞선 날짜의 갱신이 문서를 바꾸므로, 미리 조립해 두면 낡은 문서를
 * base로 삼게 되고 미루는 것 자체가 무의미해진다.
 *
 * <p>배치는 한 사용자의 밀린 날들을 <b>한 요청으로 묶는다</b>. guard가 사용자당 하나라 나눠 보내면 하루에
 * 한 건씩만 처리돼 N일이 밀리면 N일이 걸린다. {@code dailyTimelines[]}가 배열이고 AI가 최대 5건을 받는
 * 것이 이 자리다.
 *
 * <p>큐는 Redis에 있어 재배포·재시작을 견디고, 여러 인스턴스가 동시에 드레인해도 안전하다 — guard를
 * 잡은 하나만 진행하고 못 잡은 쪽은 큐를 건드리지 않으므로 항목이 되살아나지 않는다.
 *
 * <p>로그에 memory 문서 내용·memo 본문·PII를 남기지 않는다. {@code taskToken} 원문은 어떤 로그에도
 * 남기지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserMemoryUpdateWorker {

    /** AI 내부 예산 120초에 여유를 둔 값(draft task와 동일). */
    static final Duration TASK_TTL = Duration.ofMinutes(3);

    /** 한 요청에 실을 수 있는 하루 수(AI 계약 상한). 초과분은 다음 배치가 가져간다. */
    static final int MAX_DAILY_TIMELINES = 5;

    /**
     * 1회 실행이 큐에서 읽어오는 최대 건수. <b>처리량 제한이 아니라 단일 Redis 응답 크기의 안전선</b>이라
     * 평상시엔 걸리지 않을 만큼 크게 잡는다. 걸리면 그만큼의 사용자가 다음 실행으로 밀리므로 경고한다.
     */
    static final int SCAN_LIMIT = 10_000;

    private final UserMemoryUpdatePendingStore pendingStore;
    private final UserMemoryUpdateTaskStore taskStore;
    private final DailyRecordService dailyRecordService;
    private final TimelineEventService timelineEventService;
    private final UserMemoryService userMemoryService;
    private final UserMemoryUpdateDispatcher dispatcher;
    private final Clock clock;

    /**
     * 저장 커밋 직후의 즉시 접수. AI가 202로 받으면 큐를 아예 거치지 않는다.
     *
     * <p><b>접수되지 못한 날은 전부 큐에 남긴다</b>(DLQ) — guard 점유든, 4xx 거절이든, 5xx·timeout이든
     * 공통점은 "아직 반영되지 않았다"이고 그게 큐의 정의다. 여기서 안 남기면 이 경로에는 재시도 근거가
     * 아무 데도 없다(task는 TTL 3분이면 사라진다). 큐 항목은 접수 실패 시각을 score로 새로 받으므로
     * 그 시점부터 {@code retention}만큼 다시 기회를 갖는다.
     *
     * <p>접수에 성공(202)했을 때만 큐를 건드리지 않는다 — 반영 확인과 정리는 결과 endpoint 몫이다.
     * 접수할 하루 기록이 사라진 경우도 남기지 않는다(갱신할 재료가 없다).
     *
     * <p>실패를 밖으로 던지지 않는다. 호출부(저장 API)는 되돌릴 것이 없다.
     */
    @Async
    public void dispatchNow(UserMemoryUpdatePending pending) {
        Instant now = clock.instant();
        try {
            Status status = process(pending.userId(), List.of(pending), now);
            if (status.requeues()) {
                pendingStore.enqueue(pending, now);
                log.info("User Memory 갱신 보류(배치가 재시도): userId={} dailyRecordId={} status={}",
                        pending.userId(), pending.dailyRecordId(), status);
            }
        } catch (RuntimeException e) {
            log.error("User Memory 갱신 즉시 접수 실패: userId={} dailyRecordId={}",
                    pending.userId(), pending.dailyRecordId(), e);
        }
    }

    /**
     * 아직 반영되지 않아 큐에 쌓인 작업을 하루 1회 다시 접수한다. 사용자별로 묶어 한 요청에 최대
     * {@link #MAX_DAILY_TIMELINES}일을 싣는다.
     *
     * <p><b>밀린 사용자를 전부 돈다 — 처리량 상한이 없다.</b> 사용자당 접수가 한 건이라 1회 실행의 일감은
     * 건수가 아니라 구별되는 사용자 수로 묶인다. 처리량을 건수로 자르면 잘려 나간 사용자가 이유 없이 하루를
     * 더 기다린다. 실행 시간은 사용자 수 × HTTP 왕복에 비례하므로 완료 로그의 {@code users}가 그 비용을
     * 보여준다. {@link #SCAN_LIMIT}은 처리량이 아니라 단일 Redis 응답 크기의 안전선이다.
     *
     * <p><b>한 사용자에게 연달아 보내지는 않는다.</b> 갱신이 "기존 문서 + 날들 → 새 문서"라 두 번째 요청의
     * base는 첫 번째의 결과여야 한다. 결과를 기다리지 않고 이어 보내면 둘 다 같은 base로 만들어져 나중에
     * 도착한 쪽이 지문 대조에서 폐기되고({@link UserMemoryUpdateResultService}) 큐로 되돌아온다 — AI
     * 연산만 태우고 결과는 다음 실행으로 미뤄지는 것과 같다. 그래서 6일째부터는 애초에 다음 실행 몫이다.
     *
     * <p><b>여기서도 결과를 기다리지 않고 큐도 비우지 않는다.</b> 반영이 확인되면 결과 endpoint가 큐에서
     * 뺀다 — 그래서 AI가 FAILED를 주거나 아예 응답하지 않으면 항목이 그대로 남아 다음 실행이 다시 시도한다.
     * 걷어내는 경우는 <b>갱신할 재료가 사라졌을 때 하나뿐이다</b>. 4xx 거절도 남긴다 — 계약 불일치처럼
     * 우리 쪽 수정으로 풀리는 4xx가 있고, 그걸 지우면 고친 뒤에도 그 날은 복구되지 않는다.
     */
    @Scheduled(cron = "${app.user-memory.update.retry-cron:0 30 4 * * *}")
    public void retryPendingUpdates() {
        Instant now = clock.instant();
        UserMemoryUpdatePendingStore.PendingScan scan = pendingStore.findPending(now, SCAN_LIMIT);
        if (scan.scanned().isEmpty()) {
            return;
        }
        if (scan.total() > scan.scanned().size()) {
            // 안전선에 걸렸다 = 그만큼의 사용자가 조회조차 되지 못하고 다음 실행으로 밀렸다.
            log.warn("User Memory 갱신 대기 큐가 조회 상한을 넘었습니다: pendingDays={} scanned={} limit={}",
                    scan.total(), scan.scanned().size(), SCAN_LIMIT);
        }
        Map<Long, List<UserMemoryUpdatePending>> byUser = scan.scanned().stream()
                .collect(Collectors.groupingBy(UserMemoryUpdatePending::userId,
                        LinkedHashMap::new, Collectors.toList()));
        int dispatchedUsers = 0;
        int dispatchedDays = 0;
        int abandonedDays = 0;
        for (Map.Entry<Long, List<UserMemoryUpdatePending>> entry : byUser.entrySet()) {
            // 사용자당 상한 초과분은 큐에 남겨 다음 실행이 가져간다.
            List<UserMemoryUpdatePending> batch = entry.getValue().stream().limit(MAX_DAILY_TIMELINES).toList();
            try {
                Status status = process(entry.getKey(), batch, now);
                if (status == Status.NO_MATERIAL) {
                    batch.forEach(pendingStore::remove);
                    abandonedDays += batch.size();
                } else if (status == Status.ACCEPTED) {
                    dispatchedUsers++;
                    dispatchedDays += batch.size();
                }
            } catch (RuntimeException e) {
                // 한 사용자의 실패가 나머지 드레인을 막지 않는다.
                log.error("User Memory 갱신 재시도 실패: userId={} pendingDays={}",
                        entry.getKey(), batch.size(), e);
            }
        }
        // pendingDays가 곧 큐 적체다. deferredDays가 계속 남으면 하루 1회 주기가 부족하다는 신호다.
        log.info("User Memory 갱신 재시도 배치 완료: pendingDays={} users={} dispatchedUsers={} "
                        + "dispatchedDays={} abandonedDays={} deferredDays={}",
                scan.total(), byUser.size(), dispatchedUsers, dispatchedDays, abandonedDays,
                scan.total() - dispatchedDays - abandonedDays);
    }

    /**
     * 사용자 guard를 잡고 그 사용자의 작업을 한 요청으로 접수한다. <b>큐는 건드리지 않는다</b> —
     * 반환한 {@link Status}를 보고 호출부가 넣거나(즉시 접수) 지운다(배치).
     *
     * @param batch 이미 {@link #MAX_DAILY_TIMELINES} 이하로 잘린 목록
     */
    private Status process(long userId, List<UserMemoryUpdatePending> batch, Instant now) {
        String taskId = UUID.randomUUID().toString();
        if (!taskStore.acquireGuard(userId, taskId, TASK_TTL)) {
            return Status.GUARD_BUSY;
        }

        try {
            return dispatch(userId, batch, taskId, now);
        } catch (TimelineAiDispatchRejectedException e) {
            // 4xx = 미접수 확정. 결과가 올 일이 없으므로 task를 남기지 않는다 — 다만 날은 큐에 남긴다.
            taskStore.delete(taskId);
            taskStore.releaseGuard(userId);
            log.error("User Memory 갱신 접수 거절: userId={} days={} taskId={}",
                    userId, batch.size(), taskId, e);
            return Status.REJECTED;
        } catch (RuntimeException e) {
            // AI가 이미 받았을 수 있으므로 task와 guard를 남긴다(결과가 오면 정상 종결된다).
            log.error("User Memory 갱신 접수 결과 불명: userId={} days={} taskId={}",
                    userId, batch.size(), taskId, e);
            return Status.UNKNOWN;
        }
    }

    /** guard를 잡은 뒤에만 호출된다 — 여기서 읽는 record·event·memory가 접수 body의 권위다. */
    private Status dispatch(long userId, List<UserMemoryUpdatePending> batch, String taskId, Instant now) {
        List<DailyRecord> records = batch.stream()
                .map(pending -> dailyRecordService
                        .findByDailyRecordIdAndUserId(pending.dailyRecordId(), userId).orElse(null))
                .filter(Objects::nonNull)
                .toList();
        if (records.isEmpty()) {
            // 저장 후 사용자가 하루 기록을 지웠다 — 갱신할 재료가 없다.
            taskStore.delete(taskId);
            taskStore.releaseGuard(userId);
            log.info("User Memory 갱신 취소(하루 기록 없음): userId={} days={}", userId, batch.size());
            return Status.NO_MATERIAL;
        }

        String taskToken = TaskTokens.generate();
        var baseMemory = userMemoryService.find(userId);
        taskStore.save(taskId, new UserMemoryUpdateTask(userId,
                records.stream().map(DailyRecord::getDailyRecordId).toList(),
                TaskTokens.hash(taskToken), now, UserMemoryDigest.of(baseMemory)), TASK_TTL);

        dispatcher.dispatch(new AiUserMemoryUpdateRequest(taskId, taskToken, baseMemory.orElse(null),
                records.stream().map(this::toDailyTimeline).toList()));
        log.info("User Memory 갱신 접수 요청: userId={} days={} taskId={}", userId, records.size(), taskId);
        return Status.ACCEPTED;
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

    /** 접수 시도의 결과. 호출부가 큐를 어떻게 할지 이 값 하나로 정한다. */
    private enum Status {
        /** 그 사용자의 다른 갱신이 진행 중 — 실패가 아니라 정상 직렬화다. */
        GUARD_BUSY,
        /** AI가 202로 접수했다. 반영 확인과 큐 정리는 결과 endpoint가 한다. */
        ACCEPTED,
        /** 미접수 확정(4xx). 이 payload로는 결과가 같지만 계약 불일치처럼 우리 쪽 수정으로 풀리는 4xx가 있다. */
        REJECTED,
        /** 5xx·timeout·접수 계약 불일치 — AI가 받았을 수도 있다. 결과가 안 오면 다음 배치가 다시 보낸다. */
        UNKNOWN,
        /** 접수할 하루 기록이 사라졌다 — 갱신할 재료가 없으므로 큐에서 걷어낸다. */
        NO_MATERIAL;

        /** 아직 반영되지 않았고 다시 시도할 수 있다 = 큐에 있어야 한다. */
        boolean requeues() {
            return this == GUARD_BUSY || this == REJECTED || this == UNKNOWN;
        }
    }
}
