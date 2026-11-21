package com.laimory.server.aidispatch;

import java.time.Duration;
import java.util.List;

/**
 * AI 접수 대기열 포트(#515). 운영 구현은 SQS({@link SqsDispatchQueue})이고 큐 기술은 이 interface 뒤로만 바뀐다.
 *
 * <p>구현이 지켜야 할 의미: at-least-once 전달, 받은 메시지는 {@link #delete} 전까지 잠시 숨겨졌다가(visibility)
 * 다시 전달됨, 정해진 횟수 넘게 처리되지 못한 메시지는 dead-letter로 이동. 메시지 본문에는 식별자만 싣는다 — token·
 * 사용자 원문을 싣지 않는다.
 */
public interface DispatchQueue {

    /**
     * 메시지를 적재한다. 적재되지 않았음이 확실하면 {@link DispatchQueueSendRejectedException}을 던진다. 그 밖의 예외
     * (timeout·전송 실패·5xx)는 적재 여부 불명이다.
     */
    void send(String body);

    /** 최대 {@code maxMessages}개(1~10)를 받는다. 메시지가 없으면 구현이 정한 시간만큼 기다린 뒤 빈 목록을 준다. */
    List<ReceivedDispatch> receive(int maxMessages);

    /** 처리가 끝난 메시지를 지운다. */
    void delete(String receiptHandle);

    /** 메시지를 {@code delay} 뒤에 다시 받을 수 있게 한다(받은 횟수는 그대로 누적된다). */
    void retryAfter(String receiptHandle, Duration delay);
}
