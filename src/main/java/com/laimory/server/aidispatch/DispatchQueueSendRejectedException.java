package com.laimory.server.aidispatch;

/** 대기열이 적재 요청을 거절해 메시지가 적재되지 않았음이 확실하다(#515, SQS 4xx). */
public class DispatchQueueSendRejectedException extends RuntimeException {

    public DispatchQueueSendRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
