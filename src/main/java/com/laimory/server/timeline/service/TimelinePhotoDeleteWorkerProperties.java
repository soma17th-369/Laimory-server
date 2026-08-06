package com.laimory.server.timeline.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** PHOTO delete-job worker의 runtime 설정과 기동 시 불변식 검증. */
@Component
public class TimelinePhotoDeleteWorkerProperties {

    private static final int MAX_BATCH_SIZE = 1_000;

    private final boolean workerEnabled;
    private final int batchSize;

    public TimelinePhotoDeleteWorkerProperties(
            @Value("${app.timeline.photo-delete.worker-enabled:false}") boolean workerEnabled,
            @Value("${app.timeline.photo-delete.batch-size:1000}") int batchSize) {
        if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalStateException(
                    "app.timeline.photo-delete.batch-size must be between 1 and " + MAX_BATCH_SIZE);
        }
        this.workerEnabled = workerEnabled;
        this.batchSize = batchSize;
    }

    public boolean isWorkerEnabled() {
        return workerEnabled;
    }

    public int getBatchSize() {
        return batchSize;
    }
}
