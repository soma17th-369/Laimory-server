package com.laimory.server.timeline.service;

import com.laimory.server.timeline.dto.AiUserMemoryUpdateRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * no-op 디스패처(기본 구현). AI 서버 미연동 상태에서 taskId만 로그하고 끝낸다 —
 * 결과가 오지 않으므로 task는 TTL로 소멸하고 User Memory는 원본 그대로 남는다.
 *
 * <p>{@code matchIfMissing = true}라 {@code app.ai.mode} 미설정 환경(prod 포함)에서 항상 이 구현이 선택된다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.ai.mode", havingValue = "noop", matchIfMissing = true)
public class NoOpUserMemoryUpdateDispatcher implements UserMemoryUpdateDispatcher {

    @Override
    public void dispatch(AiUserMemoryUpdateRequest request) {
        // taskToken은 의도적으로 로그에서 제외한다.
        log.info("no-op user memory update dispatch: taskId={} dailyTimelines={}",
                request.taskId(), request.dailyTimelines().size());
    }
}
