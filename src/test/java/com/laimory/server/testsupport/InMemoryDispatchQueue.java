package com.laimory.server.testsupport;

import com.laimory.server.aidispatch.DispatchQueue;
import com.laimory.server.aidispatch.ReceivedDispatch;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 테스트용 접수 대기열(#515). 적재된 본문, 받을 메시지, 지운·다시 받기로 한 receipt를 기록한다. 실제 visibility·
 * dead-letter 동작은 흉내 내지 않는다 — 그 의미는 SQS가 소유하고 dev 실환경에서 본다.
 */
public class InMemoryDispatchQueue implements DispatchQueue {

    private final List<String> sent = Collections.synchronizedList(new ArrayList<>());
    private final ConcurrentLinkedQueue<ReceivedDispatch> receivable = new ConcurrentLinkedQueue<>();
    private final List<String> deleted = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Duration> retried = new ConcurrentHashMap<>();
    private volatile RuntimeException sendFailure;

    /** 다음 {@link #receive}가 돌려줄 메시지를 넣는다. */
    public void offer(ReceivedDispatch message) {
        receivable.add(message);
    }

    /** 이후 {@link #send}가 이 예외를 던지게 한다. */
    public void failSendsWith(RuntimeException failure) {
        this.sendFailure = failure;
    }

    @Override
    public void send(String body) {
        if (sendFailure != null) {
            throw sendFailure;
        }
        sent.add(body);
    }

    @Override
    public List<ReceivedDispatch> receive(int maxMessages) {
        List<ReceivedDispatch> batch = new ArrayList<>();
        ReceivedDispatch next;
        while (batch.size() < maxMessages && (next = receivable.poll()) != null) {
            batch.add(next);
        }
        if (batch.isEmpty()) {
            // long polling 흉내 — 빈 큐에서 worker poller가 헛돌지 않게 잠깐 쉰다.
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return batch;
    }

    @Override
    public void delete(String receiptHandle) {
        deleted.add(receiptHandle);
    }

    @Override
    public void retryAfter(String receiptHandle, Duration delay) {
        retried.put(receiptHandle, delay);
    }

    public List<String> sent() {
        return List.copyOf(sent);
    }

    public List<String> deleted() {
        return List.copyOf(deleted);
    }

    public Map<String, Duration> retried() {
        return Map.copyOf(retried);
    }
}
