package com.laimory.server.user.service;

import com.laimory.server.timeline.service.TimelineTaskService;
import com.laimory.server.timeline.service.UserMemoryUpdateWorker;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 계정 삭제 worker의 runtime 설정과 기동 시 불변식 검증(#302).
 *
 * <p>두 pass가 서로 다른 축을 쓴다.
 * <ul>
 *   <li><b>정지</b>({@code PENDING → QUIESCED}) — 접수 후 {@code quiesceDelay}가 지나면 곧바로 대상.
 *       짧은 cron으로 돌며 데이터는 지우지 않고 User Memory 미반영 큐만 비운다.</li>
 *   <li><b>삭제</b>({@code QUIESCED → 행 삭제}) — 접수일 D 기준 D+{@code gracePeriodDays}+1 부터
 *       {@code windowDays}일 동안만 시도한다. 일일 cron이며 PHOTO 삭제 job(#365)과 같은 규칙이다.</li>
 * </ul>
 *
 * <p><b>{@code quiesceDelay}가 즉시가 아닌 이유</b>: 탈퇴 시점에 살아 있던 AI 작업의 결과가 도착하면
 * 실패 통보가 미반영 큐를 다시 채운다. 그래서 draft/User Memory task TTL과 presigned PUT 수명을 모두
 * 넘긴 뒤에 큐를 비워야 다시 채워지지 않는다. 두 TTL은 컴파일 시점 상수라 직접 읽고, presign TTL만
 * 환경변수로 바뀔 수 있어 주입값을 검증한다 — 이 환경변수만 올리면 근거가 조용히 깨지기 때문이다.
 *
 * <p><b>{@code staleAfter <= quiesceDelay}가 필요한 이유</b>: 접수 native insert가 {@code created_at}과
 * {@code updated_at}에 같은 값을 넣으므로, 한 번도 claim되지 않은 행에서 두 조건의 실효 gate는
 * {@code max(quiesceDelay, staleAfter)}가 된다. 이 부등식이 없으면 {@code staleAfter}를 크게 잡는 순간
 * 정지가 조용히 그만큼 늦어진다.
 */
@Component
public class AccountErasureWorkerProperties {

    /**
     * 한 claim이 잠그는 job 수의 상한. 기본값이 1인 이유: claim은 선택한 행의 {@code updated_at}을
     * 오늘로 찍어 그날 재선택을 막는데, 여러 건을 한 번에 잡아 놓고 실행 예산이 끝나면 시작도 못 한
     * 행이 하루를 통째로 날린다(처리 창이 3일뿐이다). 탈퇴 건수는 원래 적어서 한 건씩 잡아도 충분하다.
     */
    private static final int MAX_BATCH_SIZE = 500;
    private static final int MAX_CONCURRENCY = 2;
    private static final int MAX_BATCHES_PER_RUN = 1_000;
    private static final Duration MAX_RUN_DURATION = Duration.ofMinutes(10);
    private static final int MAX_GRACE_PERIOD_DAYS = 365;
    private static final int MAX_WINDOW_DAYS = 30;

    /** 살아 있는 AI 작업·presign이 모두 만료됐다고 볼 수 있는 시각에 더하는 여유. */
    private static final Duration QUIESCE_MARGIN = Duration.ofMinutes(5);

    private final boolean workerEnabled;
    private final Duration quiesceDelay;
    private final Duration staleAfter;
    private final int gracePeriodDays;
    private final int windowDays;
    private final int batchSize;
    private final int concurrency;
    private final int maxBatchesPerRun;
    private final Duration maxRunDuration;

    public AccountErasureWorkerProperties(
            @Value("${app.account-erasure.worker-enabled:true}") boolean workerEnabled,
            @Value("${app.account-erasure.quiesce-delay:20m}") Duration quiesceDelay,
            @Value("${app.account-erasure.stale-after:15m}") Duration staleAfter,
            @Value("${app.account-erasure.grace-period-days:7}") int gracePeriodDays,
            @Value("${app.account-erasure.window-days:3}") int windowDays,
            @Value("${app.account-erasure.batch-size:1}") int batchSize,
            @Value("${app.account-erasure.concurrency:1}") int concurrency,
            @Value("${app.account-erasure.max-batches-per-run:100}") int maxBatchesPerRun,
            @Value("${app.account-erasure.max-run-duration:120s}") Duration maxRunDuration,
            @Value("${photo.upload.presign-ttl}") Duration presignTtl) {
        Duration quiesceFloor = maxOf(
                TimelineTaskService.PROCESSING_TTL, UserMemoryUpdateWorker.TASK_TTL, presignTtl)
                .plus(QUIESCE_MARGIN);
        if (quiesceDelay.compareTo(quiesceFloor) < 0) {
            throw new IllegalStateException("app.account-erasure.quiesce-delay must be at least " + quiesceFloor
                    + " (max live AI task/presign TTL + margin) — 정지가 살아 있는 작업보다 먼저 오면"
                    + " 미반영 큐가 다시 채워진다");
        }
        if (staleAfter.isZero() || staleAfter.isNegative() || staleAfter.compareTo(quiesceDelay) > 0) {
            throw new IllegalStateException(
                    "app.account-erasure.stale-after must be positive and at most quiesce-delay"
                            + " — 크면 정지 시점이 그만큼 늦어진다");
        }
        if (gracePeriodDays < 1 || gracePeriodDays > MAX_GRACE_PERIOD_DAYS) {
            throw new IllegalStateException("app.account-erasure.grace-period-days must be between 1 and "
                    + MAX_GRACE_PERIOD_DAYS);
        }
        if (windowDays < 1 || windowDays > MAX_WINDOW_DAYS) {
            throw new IllegalStateException(
                    "app.account-erasure.window-days must be between 1 and " + MAX_WINDOW_DAYS);
        }
        if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalStateException(
                    "app.account-erasure.batch-size must be between 1 and " + MAX_BATCH_SIZE);
        }
        if (concurrency < 1 || concurrency > MAX_CONCURRENCY) {
            throw new IllegalStateException(
                    "app.account-erasure.concurrency must be between 1 and " + MAX_CONCURRENCY);
        }
        if (maxBatchesPerRun < 1 || maxBatchesPerRun > MAX_BATCHES_PER_RUN) {
            throw new IllegalStateException("app.account-erasure.max-batches-per-run must be between 1 and "
                    + MAX_BATCHES_PER_RUN);
        }
        if (maxRunDuration.isZero() || maxRunDuration.isNegative()
                || maxRunDuration.compareTo(MAX_RUN_DURATION) > 0) {
            throw new IllegalStateException("app.account-erasure.max-run-duration must be positive and at most "
                    + MAX_RUN_DURATION);
        }
        this.workerEnabled = workerEnabled;
        this.quiesceDelay = quiesceDelay;
        this.staleAfter = staleAfter;
        this.gracePeriodDays = gracePeriodDays;
        this.windowDays = windowDays;
        this.batchSize = batchSize;
        this.concurrency = concurrency;
        this.maxBatchesPerRun = maxBatchesPerRun;
        this.maxRunDuration = maxRunDuration;
    }

    private static Duration maxOf(Duration first, Duration second, Duration third) {
        Duration max = first.compareTo(second) >= 0 ? first : second;
        return max.compareTo(third) >= 0 ? max : third;
    }

    public boolean isWorkerEnabled() {
        return workerEnabled;
    }

    public Duration getQuiesceDelay() {
        return quiesceDelay;
    }

    public Duration getStaleAfter() {
        return staleAfter;
    }

    public int getGracePeriodDays() {
        return gracePeriodDays;
    }

    public int getWindowDays() {
        return windowDays;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public int getConcurrency() {
        return concurrency;
    }

    public int getMaxBatchesPerRun() {
        return maxBatchesPerRun;
    }

    public Duration getMaxRunDuration() {
        return maxRunDuration;
    }
}
