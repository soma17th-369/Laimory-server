package com.laimory.server.timeline.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 고정 담당 워커의 단일 배치 설정. workerId는 서버 번호, workerCount는 서버 내 실행 slot 수다. */
@Component
public class TimelineDraftCleanupWorkerProperties {

    private final boolean workerEnabled;
    private final long retentionDays;
    private final int batchSize;
    private final int workerId;
    private final int serverCount;
    private final int workerCount;

    public TimelineDraftCleanupWorkerProperties(
            @Value("${app.draft.worker-enabled:true}") boolean workerEnabled,
            @Value("${app.draft.retention-days:7}") long retentionDays,
            @Value("${app.draft.batch-size:250}") int batchSize,
            @Value("${app.draft.worker-id:0}") int workerId,
            @Value("${app.draft.server-count:2}") int serverCount,
            @Value("${app.draft.worker-count:1}") int workerCount) {
        // PROCESSING TTL(3분)보다 충분히 길게 유지해 진행 중 source가 정리되지 않도록 한다.
        if (retentionDays < 1) {
            throw new IllegalStateException("app.draft.retention-days must be at least 1");
        }
        if (batchSize < 1 || batchSize > 1_000) {
            throw new IllegalStateException("app.draft.batch-size must be between 1 and 1000");
        }
        if (serverCount < 1 || workerCount < 1 || workerId < 0 || workerId >= serverCount) {
            throw new IllegalStateException("app.draft: server-count and worker-count must be positive; "
                    + "worker-id must be between 0 (inclusive) and server-count (exclusive)");
        }
        Math.multiplyExact(serverCount, workerCount);
        this.workerEnabled = workerEnabled;
        this.retentionDays = retentionDays;
        this.batchSize = batchSize;
        this.workerId = workerId;
        this.serverCount = serverCount;
        this.workerCount = workerCount;
    }

    public boolean isWorkerEnabled() {
        return workerEnabled;
    }

    public long getRetentionDays() {
        return retentionDays;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public int getWorkerCount() {
        return workerCount;
    }

    public int getTotalWorkerCount() {
        return serverCount * workerCount;
    }

    public int getWorkerIndex(int localIndex) {
        if (localIndex < 0 || localIndex >= workerCount) {
            throw new IllegalArgumentException("localIndex must identify a worker slot");
        }
        return workerId * workerCount + localIndex;
    }
}
