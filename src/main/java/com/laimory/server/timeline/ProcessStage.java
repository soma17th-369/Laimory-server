package com.laimory.server.timeline;

/** Redis PROCESSING task의 서버 간 처리 단계. 외부 polling 상태에는 노출하지 않는다. */
public enum ProcessStage {
    INPUT_PENDING,
    RESULT_PENDING,
    CALLBACK_PENDING
}
