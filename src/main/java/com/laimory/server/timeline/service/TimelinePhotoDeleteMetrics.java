package com.laimory.server.timeline.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * PHOTO delete-job lifecycle의 저카디널리티 업무 메트릭.
 *
 * <p>worker가 꺼져 있어도 queue backlog를 볼 수 있도록 gauge는 항상 등록한다. object key와 Item/job ID는
 * tag로 사용하지 않는다.
 */
@Component
public class TimelinePhotoDeleteMetrics {

    static final String DELETE_ATTEMPT = "laimory.timeline.photo.delete.attempt";
    static final String DELETE_PENDING = "laimory.timeline.photo.delete.pending";
    static final String DELETE_OLDEST_AGE = "laimory.timeline.photo.delete.oldest.age";
    static final String DELETE_BATCH_DURATION = "laimory.timeline.photo.delete.batch.duration";
    static final String DELETE_ENQUEUE = "laimory.timeline.photo.delete.enqueue";

    private final Counter attemptSuccess;
    private final Counter attemptFailed;
    private final Counter enqueueScheduled;
    private final Counter enqueueSharedRetained;
    private final Counter enqueueInvalidSkipped;
    private final Timer batchDuration;
    private final MeterRegistry meterRegistry;
    private final TimelinePhotoDeleteJobService jobService;
    private final Clock clock;

    public TimelinePhotoDeleteMetrics(
            MeterRegistry meterRegistry,
            TimelinePhotoDeleteJobService jobService,
            Clock clock) {
        this.meterRegistry = meterRegistry;
        this.jobService = jobService;
        this.clock = clock;
        this.attemptSuccess = attemptCounter(meterRegistry, "success");
        this.attemptFailed = attemptCounter(meterRegistry, "failed");
        this.enqueueScheduled = enqueueCounter(meterRegistry, "scheduled");
        this.enqueueSharedRetained = enqueueCounter(meterRegistry, "shared_retained");
        this.enqueueInvalidSkipped = enqueueCounter(meterRegistry, "invalid_skipped");
        this.batchDuration = Timer.builder(DELETE_BATCH_DURATION)
                .description("S3 PHOTO delete batch call duration")
                .register(meterRegistry);
        Gauge.builder(DELETE_PENDING, this, TimelinePhotoDeleteMetrics::pendingOrNaN)
                .description("Pending PHOTO delete job rows")
                .register(meterRegistry);
        Gauge.builder(DELETE_OLDEST_AGE, this, TimelinePhotoDeleteMetrics::oldestAgeSecondsOrNaN)
                .description("Age in seconds of the oldest pending PHOTO delete job")
                .register(meterRegistry);
    }

    private static Counter attemptCounter(MeterRegistry registry, String result) {
        return Counter.builder(DELETE_ATTEMPT)
                .description("Per-object S3 PHOTO delete results")
                .tag("result", result)
                .register(registry);
    }

    private static Counter enqueueCounter(MeterRegistry registry, String result) {
        return Counter.builder(DELETE_ENQUEUE)
                .description("PHOTO delete enqueue decisions")
                .tag("result", result)
                .register(registry);
    }

    public void recordAttemptSuccess(int count) {
        increment(attemptSuccess, count);
    }

    public void recordAttemptFailed(int count) {
        increment(attemptFailed, count);
    }

    public void recordEnqueueScheduled(int count) {
        increment(enqueueScheduled, count);
    }

    public void recordEnqueueSharedRetained(int count) {
        increment(enqueueSharedRetained, count);
    }

    public void recordEnqueueInvalidSkipped(int count) {
        increment(enqueueInvalidSkipped, count);
    }

    public Timer.Sample startBatch() {
        return Timer.start(meterRegistry);
    }

    public void recordBatch(Timer.Sample sample) {
        sample.stop(batchDuration);
    }

    private double pendingOrNaN() {
        try {
            return jobService.countPending();
        } catch (RuntimeException ignored) {
            return Double.NaN;
        }
    }

    private double oldestAgeSecondsOrNaN() {
        try {
            Optional<LocalDateTime> oldest = jobService.findOldestCreatedAt();
            if (oldest.isEmpty()) {
                return 0;
            }
            LocalDateTime now = LocalDateTime.now(clock);
            return Math.max(0, Duration.between(oldest.orElseThrow(), now).toSeconds());
        } catch (RuntimeException ignored) {
            return Double.NaN;
        }
    }

    private static void increment(Counter counter, int count) {
        if (count > 0) {
            counter.increment(count);
        }
    }
}
