package com.laimory.server.timeline.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 고정 담당 워커의 단일 배치 설정. workerId는 서버 번호, workerCount는 서버 내 실행 slot 수다. */
@Component
public class TimelinePhotoDeleteWorkerProperties {

    private final boolean workerEnabled;
    private final int batchSize;
    private final int workerId;
    private final int serverCount;
    private final int workerCount;

    public TimelinePhotoDeleteWorkerProperties(
            @Value("${app.timeline.photo-delete.worker-enabled:true}") boolean workerEnabled,
            @Value("${app.timeline.photo-delete.batch-size:250}") int batchSize,
            @Value("${app.timeline.photo-delete.worker-id:0}") int workerId,
            @Value("${app.timeline.photo-delete.server-count:2}") int serverCount,
            @Value("${app.timeline.photo-delete.worker-count:1}") int workerCount) {
        if (batchSize < 1 || batchSize > 1_000) {
            throw new IllegalStateException("app.timeline.photo-delete.batch-size must be between 1 and 1000");
        }
        if (serverCount < 1 || workerCount < 1 || workerId < 0 || workerId >= serverCount) {
            throw new IllegalStateException("app.timeline.photo-delete: server-count and worker-count must be positive; "
                    + "worker-id must be between 0 (inclusive) and server-count (exclusive)");
        }
        Math.multiplyExact(serverCount, workerCount);
        this.workerEnabled = workerEnabled;
        this.batchSize = batchSize;
        this.workerId = workerId;
        this.serverCount = serverCount;
        this.workerCount = workerCount;
    }

    public boolean isWorkerEnabled() {
        return workerEnabled;
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
