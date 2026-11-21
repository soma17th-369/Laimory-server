package com.laimory.server.aidispatch;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SqsException;

/**
 * SQS Standard 대기열 어댑터(#515). AWS SDK 타입은 이 클래스 밖으로 나가지 않는다.
 *
 * <p>SDK 기본 재시도를 유지한다 — 적재가 중복돼도 worker의 처리권·처리 단계 검사가 두 번째를 버린다(AgentCore 접수의
 * {@code doNotRetry}와 반대). 적재는 요청 스레드에서 일어나므로 시도·전체 상한을 짧게 걸고, 수신은 long polling
 * ({@link #RECEIVE_WAIT})보다 긴 상한을 건다.
 */
public class SqsDispatchQueue implements DispatchQueue {

    /** long polling 대기. SQS 상한(20s)이다. */
    static final Duration RECEIVE_WAIT = Duration.ofSeconds(20);
    private static final Duration SEND_ATTEMPT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration SEND_CALL_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration RECEIVE_CALL_TIMEOUT = RECEIVE_WAIT.plusSeconds(10);

    private final SqsClient client;
    private final String queueUrl;

    public SqsDispatchQueue(SqsClient client, String queueUrl) {
        this.client = client;
        this.queueUrl = queueUrl;
    }

    @Override
    public void send(String body) {
        SendMessageRequest request = SendMessageRequest.builder()
                .queueUrl(queueUrl)
                .messageBody(body)
                .overrideConfiguration(override -> override
                        .apiCallAttemptTimeout(SEND_ATTEMPT_TIMEOUT)
                        .apiCallTimeout(SEND_CALL_TIMEOUT))
                .build();
        try {
            client.sendMessage(request);
        } catch (SqsException e) {
            if (e.statusCode() >= 400 && e.statusCode() < 500) {
                // 요청 검증·권한·큐 부재로 거절 = 적재되지 않음이 확실하다. 본문은 예외 메시지에 싣지 않는다.
                throw new DispatchQueueSendRejectedException("SQS가 적재를 거절했습니다(%d %s)".formatted(e.statusCode(),
                        e.awsErrorDetails() == null ? null : e.awsErrorDetails().errorCode()), e);
            }
            throw e;
        }
    }

    @Override
    public List<ReceivedDispatch> receive(int maxMessages) {
        ReceiveMessageRequest request = ReceiveMessageRequest.builder()
                .queueUrl(queueUrl)
                .maxNumberOfMessages(maxMessages)
                .waitTimeSeconds((int) RECEIVE_WAIT.toSeconds())
                .messageSystemAttributeNames(MessageSystemAttributeName.SENT_TIMESTAMP,
                        MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT)
                .overrideConfiguration(override -> override.apiCallTimeout(RECEIVE_CALL_TIMEOUT))
                .build();
        return client.receiveMessage(request).messages().stream()
                .map(message -> new ReceivedDispatch(message.body(), message.receiptHandle(),
                        parseIntOrZero(message.attributes().get(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT)),
                        parseInstantOrNull(message.attributes().get(MessageSystemAttributeName.SENT_TIMESTAMP))))
                .toList();
    }

    @Override
    public void delete(String receiptHandle) {
        client.deleteMessage(DeleteMessageRequest.builder()
                .queueUrl(queueUrl)
                .receiptHandle(receiptHandle)
                .build());
    }

    @Override
    public void retryAfter(String receiptHandle, Duration delay) {
        client.changeMessageVisibility(ChangeMessageVisibilityRequest.builder()
                .queueUrl(queueUrl)
                .receiptHandle(receiptHandle)
                .visibilityTimeout((int) delay.toSeconds())
                .build());
    }

    private static int parseIntOrZero(String value) {
        return value == null ? 0 : Integer.parseInt(value);
    }

    private static Instant parseInstantOrNull(String epochMillis) {
        return epochMillis == null ? null : Instant.ofEpochMilli(Long.parseLong(epochMillis));
    }
}
