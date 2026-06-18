package com.laimory.server.timeline;

/**
 * timeline draft 작업의 비동기 처리 상태.
 * PROCESSING(처리중) -> SUCCESS(성공) | FAILED(실패).
 */
public enum TaskStatus {
    PROCESSING,
    SUCCESS,
    FAILED
}
