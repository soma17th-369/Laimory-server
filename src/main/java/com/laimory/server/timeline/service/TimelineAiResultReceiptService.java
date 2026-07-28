package com.laimory.server.timeline.service;

import com.laimory.server.timeline.entity.TimelineAiResultReceipt;
import com.laimory.server.timeline.repository.TimelineAiResultReceiptRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * timeline_ai_result_receipts leaf 서비스. 자신과 1:1인 TimelineAiResultReceiptRepository에만 접근한다.
 *
 * <p>{@link #saveNew}는 반드시 flush까지 수행한다 — 중복 task를 duplicate key로 <b>그 자리에서</b> 드러내야
 * 호출부가 "이미 반영된 재시도"로 변환할 수 있고, transaction 끝의 flush까지 미루면 그 사이 graph write가
 * 헛돌기 때문이다.
 */
@Service
@RequiredArgsConstructor
public class TimelineAiResultReceiptService {

    private final TimelineAiResultReceiptRepository timelineAiResultReceiptRepository;

    /** 영수증을 INSERT하고 즉시 flush한다. 같은 taskId가 이미 있으면 duplicate key 예외가 전파된다. */
    public TimelineAiResultReceipt saveNew(TimelineAiResultReceipt receipt) {
        return timelineAiResultReceiptRepository.saveAndFlush(receipt);
    }

    public boolean exists(String taskId) {
        return timelineAiResultReceiptRepository.existsById(taskId);
    }

    /** 보관기간 초과 영수증을 일괄 삭제하고 삭제 행 수를 반환한다(cleanup 스케줄러용). */
    public long deleteCreatedBefore(LocalDateTime cutoff) {
        return timelineAiResultReceiptRepository.deleteByCreatedAtBefore(cutoff);
    }
}
