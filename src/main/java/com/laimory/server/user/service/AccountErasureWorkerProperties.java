package com.laimory.server.user.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 계정 삭제 worker의 runtime 설정과 기동 시 검증(#302).
 *
 * <p>유예·처리 창 일수는 여기 없다 — 공개 약관의 "탈퇴 접수일로부터 5일 이내 파기"에 묶인 값이라
 * 운영 중 바꿀 설정이 아니고, {@link AccountErasureWorker}의 상수로 둔다(#397).
 */
@Component
public class AccountErasureWorkerProperties {

    private static final int MAX_CONCURRENCY = 2;
    private static final int MAX_JOBS_PER_RUN = 1_000;

    private final boolean workerEnabled;
    private final int concurrency;
    private final int maxJobsPerRun;

    public AccountErasureWorkerProperties(
            @Value("${app.account-erasure.worker-enabled:true}") boolean workerEnabled,
            @Value("${app.account-erasure.concurrency:1}") int concurrency,
            @Value("${app.account-erasure.max-jobs-per-run:100}") int maxJobsPerRun) {
        if (concurrency < 1 || concurrency > MAX_CONCURRENCY) {
            throw new IllegalStateException(
                    "app.account-erasure.concurrency must be between 1 and " + MAX_CONCURRENCY);
        }
        if (maxJobsPerRun < 1 || maxJobsPerRun > MAX_JOBS_PER_RUN) {
            throw new IllegalStateException(
                    "app.account-erasure.max-jobs-per-run must be between 1 and " + MAX_JOBS_PER_RUN);
        }
        this.workerEnabled = workerEnabled;
        this.concurrency = concurrency;
        this.maxJobsPerRun = maxJobsPerRun;
    }

    public boolean isWorkerEnabled() {
        return workerEnabled;
    }

    public int getConcurrency() {
        return concurrency;
    }

    public int getMaxJobsPerRun() {
        return maxJobsPerRun;
    }
}
