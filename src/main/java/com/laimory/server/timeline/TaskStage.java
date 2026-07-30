package com.laimory.server.timeline;

/**
 * PROCESSING draft task의 서버간 처리 단계. 외부 polling 상태와 분리된 Redis 내부 상태다.
 */
public enum TaskStage {
    INPUT_PENDING,
    RESULT_PENDING,
    RESULT_WRITING,
    CALLBACK_PENDING
}
