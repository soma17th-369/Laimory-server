package com.laimory.server.timeline.service;

import com.laimory.server.common.privacy.PrivacyRedactor;
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
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * User Memory 갱신 접수를 담당한다. 저장은 큐에 넣기만 하고, <b>접수는 하루 1회 배치 한 곳에서만</b> 한다.
 *
 * <p><b>1. 저장 직후 큐 적재({@link #enqueue})</b> — 저장 API가 커밋 뒤 호출한다. AI를 부르지 않고
 * Redis 쓰기 한 번으로 끝난다.
 *
 * <p><b>2. 하루 1회 배치({@link #dispatchPendingUpdates})</b> — 큐에 쌓인 것을 사용자별로 묶어 접수한다.
 *
 * <p><b>왜 저장 시점에 보내지 않는가</b>: AI 계약이 "202 접수 → 백그라운드 처리 → 완료 시 결과 API 호출"이라
 * 접수 성공이 반영 성공이 아니다. 접수에 성공한 날을 큐에 넣지 않으면, AI가 202를 준 뒤 결과를 주지 않을 때
 * task는 TTL 3분에 사라지고 guard도 풀리고 <b>재시도할 근거가 아무 데도 남지 않는다</b> — 그 하루가 조용히
 * 사라진다. 그래서 저장된 하루는 예외 없이 먼저 큐에 들어가고, <b>반영이 확인될 때만</b> 큐에서 빠진다
 * (outbox). 대가는 반영 지연이 최대 cron 주기(기본 24시간)라는 것인데, User Memory는 다음 타임라인 품질을
 * 높이는 보조 데이터라 즉시성이 요구되지 않는다.
 *
 * <p><b>어느 쪽도 결과를 기다리지 않는다.</b> 응답을 기다리는 척하려면 폴링을 얹어야 하고 그건 프로토콜과
 * 싸우는 짓이다. 반영 확인과 큐 정리는 성패를 실제로 아는 지점, 즉 결과
 * endpoint({@link UserMemoryUpdateResultService})가 한다 — 반영되면 큐에서 빼고, AI가 실패를 통보하면
 * 큐에 남긴다.
 *
 * <p><b>왜 guard인가</b>: 사용자 하나의 User Memory는 문서 전체를 다시 쓰는 갱신이라, 다른 날짜의 갱신이
 * 진행 중일 때 같이 시작하면 둘 다 같은 base 문서를 읽고 나중에 도착한 쪽이 이겨 하루치가 통째로 사라진다.
 * 배치는 사용자당 한 번만 보내므로 한 실행 안에서는 경합이 없고, guard가 막는 것은 <b>앞선 실행의 접수가
 * 아직 진행 중인 경우</b>다. 점유는 장애가 아니라 정상 직렬화라 버리지 않고 미룬다.
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

    /** AI 전달 Event text의 bounded redaction 상한(title/subtitle/question — 컬럼 계약 255자). */
    private static final int EVENT_TEXT_MAX_LENGTH = 255;

    /** AI 전달 memo의 bounded redaction 상한(접수 계약 500자). */
    private static final int MEMO_MAX_LENGTH = 500;

    private final UserMemoryUpdatePendingStore pendingStore;
    private final UserMemoryUpdateTaskStore taskStore;
    private final DailyRecordService dailyRecordService;
    private final TimelineEventService timelineEventService;
    private final UserMemoryService userMemoryService;
    private final UserMemoryUpdateDispatcher dispatcher;
    private final PrivacyRedactor privacyRedactor;
    private final Clock clock;

    /**
     * 저장 커밋 직후 그 하루를 갱신 대기 큐에 넣는다. AI를 부르지 않으므로 요청 스레드에서 그대로 돌린다
     * — Redis 쓰기 한 번이고, async로 넘기면 실행기 포화 시 그 하루가 유실될 뿐이다.
     *
     * <p>이미 큐에 있으면 최초 기록 시각을 유지한다(재기록으로 포기 시한이 연장되지 않는다).
     */
    public void enqueue(UserMemoryUpdatePending pending) {
        pendingStore.enqueue(pending, clock.instant());
    }

    /**
     * 큐에 쌓인 미반영 작업을 하루 1회 접수한다. 사용자별로 묶어 한 요청에 최대
     * {@link #MAX_DAILY_TIMELINES}일을 싣는다. <b>여기가 AI로 나가는 유일한 지점이다.</b>
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
     * <p><b>결과를 기다리지 않고 큐도 비우지 않는다.</b> 반영이 확인되면 결과 endpoint가 큐에서 뺀다 —
     * 그래서 AI가 FAILED를 주거나 아예 응답하지 않으면 항목이 그대로 남아 다음 실행이 다시 시도한다.
     * 4xx 거절도 남긴다 — 계약 불일치처럼 우리 쪽 수정으로 풀리는 4xx가 있고, 그걸 지우면 고친 뒤에도
     * 그 날은 복구되지 않는다. 걷어내는 경우는 <b>갱신할 재료가 사라졌을 때 하나뿐이며</b>, 그 판정은
     * 재료를 실제로 읽는 {@link #dispatch}가 한다.
     */
    @Scheduled(cron = "${app.user-memory.update.cron:0 30 4 * * *}")
    public void dispatchPendingUpdates() {
        Instant now = clock.instant();
        UserMemoryUpdatePendingStore.PendingScan scan = pendingStore.findPending(now, SCAN_LIMIT);
        if (scan.scanned().isEmpty()) {
            return;
        }
        if (scan.scanned().size() == SCAN_LIMIT) {
            // 안전선에 걸렸다 = 그만큼의 사용자가 조회조차 되지 못하고 다음 실행으로 밀렸다.
            // total과 비교하지 않는다 — 실행 중 들어온 항목은 total에는 세지지만 이번 스냅샷 밖이다.
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
            // 상한은 여기서 안 자른다 — 어느 날을 실을지는 record_date를 아는 dispatch가 고른다.
            List<UserMemoryUpdatePending> pendingOfUser = entry.getValue();
            try {
                Outcome outcome = process(entry.getKey(), pendingOfUser);
                abandonedDays += outcome.abandonedDays();
                if (outcome.status() == Status.ACCEPTED) {
                    dispatchedUsers++;
                    dispatchedDays += outcome.dispatchedDays();
                }
            } catch (RuntimeException e) {
                // 한 사용자의 실패가 나머지 드레인을 막지 않는다.
                log.error("User Memory 갱신 접수 실패: userId={} pendingDays={}",
                        entry.getKey(), pendingOfUser.size(), e);
            }
        }
        // pendingDays가 곧 큐 적체다. deferredDays가 계속 남으면 하루 1회 주기가 부족하다는 신호다.
        log.info("User Memory 갱신 배치 완료: pendingDays={} users={} dispatchedUsers={} "
                        + "dispatchedDays={} abandonedDays={} deferredDays={}",
                scan.total(), byUser.size(), dispatchedUsers, dispatchedDays, abandonedDays,
                scan.total() - dispatchedDays - abandonedDays);
    }

    /**
     * 사용자 guard를 잡고 그 사용자의 작업을 한 요청으로 접수한다.
     *
     * <p>큐에서 걷어내는 것은 <b>재료가 사라진 날뿐이고 그 판정은 {@link #dispatch} 안에서 일어난다</b> —
     * 나머지 결과는 전부 "아직 반영 안 됨"이라 큐를 그대로 둔다.
     *
     * @param pending 그 사용자의 미반영 날 전부. 상한을 적용해 실을 날을 고르는 것은 {@link #dispatch}다
     */
    private Outcome process(long userId, List<UserMemoryUpdatePending> pending) {
        String taskId = UUID.randomUUID().toString();
        if (!taskStore.acquireGuard(userId, taskId, TASK_TTL)) {
            return Outcome.of(Status.GUARD_BUSY);
        }

        try {
            return dispatch(userId, pending, taskId);
        } catch (TimelineAiDispatchRejectedException e) {
            // 4xx = 미접수 확정. 결과가 올 일이 없으므로 task를 남기지 않는다 — 다만 날은 큐에 남긴다.
            // guard는 TTL에 맡긴다(같은 실행에서 다시 보내 봐야 같은 payload라 또 4xx다).
            taskStore.delete(taskId);
            log.error("User Memory 갱신 접수 거절: userId={} pendingDays={} taskId={}",
                    userId, pending.size(), taskId, e);
            return Outcome.of(Status.REJECTED);
        } catch (RuntimeException e) {
            // AI가 이미 받았을 수 있으므로 task와 guard를 남긴다(결과가 오면 정상 종결된다).
            log.error("User Memory 갱신 접수 결과 불명: userId={} pendingDays={} taskId={}",
                    userId, pending.size(), taskId, e);
            return Outcome.of(Status.UNKNOWN);
        }
    }

    /**
     * guard를 잡은 뒤에만 호출된다 — 여기서 읽는 record·event·memory가 접수 body의 권위다.
     *
     * <p><b>어느 날을 실을지도 여기서 고른다.</b> 큐 순서는 대기 시작 시각이라 기록 날짜와 다를 수 있는데
     * (과거 날짜를 나중에 저장할 수 있다), 갱신이 "기존 문서 + 날들 → 새 문서" 접기라 <b>기록 날짜
     * 순서로</b> 접어야 한다. 큐 순서로 5개를 자르면 8/5를 먼저 접고 다음 실행에서 8/1을 접는 일이
     * 생긴다 — 요청 안에서만 정렬해서는 실행을 넘나드는 순서가 안 잡힌다. 그래서 record_date 오름차순
     * 조회 결과에서 앞의 {@link #MAX_DAILY_TIMELINES}일을 고른다.
     */
    private Outcome dispatch(long userId, List<UserMemoryUpdatePending> pending, String taskId) {
        List<Long> pendingIds = pending.stream().map(UserMemoryUpdatePending::dailyRecordId).toList();
        // record_date 오름차순 한 번의 질의. 삭제된 하루는 결과에서 빠지므로 차집합이 곧 사라진 날이다.
        List<DailyRecord> found = dailyRecordService.findAllByUserIdAndIdsOrderByRecordDate(userId, pendingIds);

        Set<Long> foundIds = found.stream().map(DailyRecord::getDailyRecordId).collect(Collectors.toSet());
        List<Long> missing = pendingIds.stream().filter(id -> !foundIds.contains(id)).toList();
        if (!missing.isEmpty()) {
            // 저장 후 사용자가 하루 기록을 지웠다 — 갱신할 재료가 없으니 다시 시도할 이유도 없다.
            // 남겨 두면 큐를 빠져나갈 길이 없어 retention까지 매 실행 사용자당 상한을 갉아먹는다.
            // 일부만 사라진 경우도 같다 — 접수 body에서 빠지는 순간 결과 endpoint가 지울 근거를 잃는다.
            pendingStore.removeAll(userId, missing);
            log.info("User Memory 갱신 재료 없음(큐에서 제거): userId={} days={}", userId, missing.size());
        }
        if (found.isEmpty()) {
            // task는 아직 저장 전이고, guard는 TTL이 반납한다(그 사용자에게 이번 실행에 할 일이 없다).
            return new Outcome(Status.NO_MATERIAL, 0, missing.size());
        }
        // 상한 초과분은 큐에 남아 다음 실행이 가져간다(그때도 가장 이른 날짜부터).
        List<DailyRecord> records = found.stream().limit(MAX_DAILY_TIMELINES).toList();

        String taskToken = TaskTokens.generate();
        var baseMemory = userMemoryService.find(userId);
        // AI body에 실리는 base 문서·Event text만 치환한다. baseMemoryHash는 반드시 DB 원본으로 계산해야
        // 결과 endpoint의 지문 대조가 유지된다(§2.5). redacted DTO를 task 저장 전에 완성하므로 redaction
        // 실패는 task 저장·dispatch 없이 전파돼 pending이 그대로 남는다(원문 fallback 금지).
        AiUserMemoryUpdateRequest redactedRequest = new AiUserMemoryUpdateRequest(taskId, taskToken,
                baseMemory.map(memory -> privacyRedactor.redactTree(memory).node()).orElse(null),
                records.stream().map(this::toDailyTimeline).toList());
        // 접수 시각은 여기서 찍는다 — 배치 진입 시각을 쓰면 밀린 시간까지 AI 소요로 집계된다.
        taskStore.save(taskId, new UserMemoryUpdateTask(userId,
                records.stream().map(DailyRecord::getDailyRecordId).toList(),
                TaskTokens.hash(taskToken), clock.instant(), UserMemoryDigest.of(baseMemory)), TASK_TTL);

        dispatcher.dispatch(redactedRequest);
        log.info("User Memory 갱신 접수 요청: userId={} days={} taskId={}", userId, records.size(), taskId);
        return new Outcome(Status.ACCEPTED, records.size(), missing.size());
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

    /**
     * DB에는 사용자 수정 원문이 남고 AI 전달 DTO에서만 치환한다. bounded 상한은 각 컬럼 계약과 같다
     * (title/subtitle/question 255, memo 500). subtitle/question/memo는 nullable — null-safe 치환.
     */
    private AiUserMemoryUpdateRequest.Event toEvent(TimelineEvent event, ZoneId recordZone) {
        return new AiUserMemoryUpdateRequest.Event(
                event.getEventType(),
                privacyRedactor.redactText(event.getTitle(), EVENT_TEXT_MAX_LENGTH).text(),
                privacyRedactor.redactText(event.getSubtitle(), EVENT_TEXT_MAX_LENGTH).text(),
                privacyRedactor.redactText(event.getQuestion(), EVENT_TEXT_MAX_LENGTH).text(),
                toOffset(event.getStartAt(), recordZone), toOffset(event.getEndAt(), recordZone),
                privacyRedactor.redactText(event.getMemo(), MEMO_MAX_LENGTH).text());
    }

    private static OffsetDateTime toOffset(LocalDateTime value, ZoneId recordZone) {
        return value == null ? null : value.atZone(recordZone).toOffsetDateTime();
    }

    /**
     * 한 사용자 처리 결과. 집계 로그가 필요로 하는 세 값을 함께 돌려준다.
     *
     * @param dispatchedDays 접수 body에 실린 날 수(접수 성공일 때만 유효)
     * @param abandonedDays  재료가 사라져 큐에서 걷어낸 날 수 — 일부만 사라져 접수는 성공한 경우에도 센다
     */
    private record Outcome(Status status, int dispatchedDays, int abandonedDays) {

        static Outcome of(Status status) {
            return new Outcome(status, 0, 0);
        }
    }

    /** 접수 시도의 결과. */
    private enum Status {
        /** 그 사용자의 앞선 갱신이 아직 진행 중 — 실패가 아니라 정상 직렬화다. */
        GUARD_BUSY,
        /** AI가 202로 접수했다. 반영 확인과 큐 정리는 결과 endpoint가 한다. */
        ACCEPTED,
        /** 미접수 확정(4xx). 이 payload로는 결과가 같지만 계약 불일치처럼 우리 쪽 수정으로 풀리는 4xx가 있다. */
        REJECTED,
        /** 5xx·timeout·접수 계약 불일치 — AI가 받았을 수도 있다. 결과가 안 오면 다음 배치가 다시 보낸다. */
        UNKNOWN,
        /** 접수할 하루 기록이 하나도 없었다 — 해당 날들은 이미 큐에서 걷어냈다. */
        NO_MATERIAL
    }
}
