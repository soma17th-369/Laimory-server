package com.laimory.server.aidispatch;

import java.time.Instant;

/**
 * 대기열에서 받은 메시지(#515).
 *
 * @param receiveCount 이번 수신을 포함한 누적 수신 횟수(SQS {@code ApproximateReceiveCount})
 * @param sentAt       적재 시각(SQS {@code SentTimestamp})
 */
public record ReceivedDispatch(String body, String receiptHandle, int receiveCount, Instant sentAt) {
}
