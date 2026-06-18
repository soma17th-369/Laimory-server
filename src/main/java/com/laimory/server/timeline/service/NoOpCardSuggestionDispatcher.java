package com.laimory.server.timeline.service;

import com.laimory.server.timeline.dto.SourceItemDto;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * v1 no-op 디스패처. 실제 AI 호출 없이 디스패치 요청만 로깅한다.
 *
 * <p>이 스켈레톤에서는 AI 서버가 자동으로 콜백하지 않으므로, 콜백 엔드포인트를 수동 호출해 흐름을 진행한다.
 * 실제 AI 연동은 후속에서 이 포트의 다른 구현으로 교체한다.
 */
@Slf4j
@Component
public class NoOpCardSuggestionDispatcher implements CardSuggestionDispatcher {

    @Override
    public void dispatch(String taskId, List<SourceItemDto> sourceItems, String callbackUrl) {
        log.info("no-op card suggestion dispatch: taskId={}, sourceItems={}, callbackUrl={}",
                taskId, sourceItems == null ? 0 : sourceItems.size(), callbackUrl);
    }
}
