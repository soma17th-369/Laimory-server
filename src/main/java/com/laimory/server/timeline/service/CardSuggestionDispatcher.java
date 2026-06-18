package com.laimory.server.timeline.service;

import com.laimory.server.timeline.dto.SourceItemDto;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 카드 제안(Card Suggestion) 생성을 외부 AI에 위임한다.
 *
 * <p>POST 흐름은 이 디스패치를 절대 블로킹하지 않는다(요청 스레드가 LLM 응답을 기다리면 톰캣 풀이 고갈됨).
 * 결과는 AI가 {@code callbackUrl}로 콜백한다. {@code dispatch}는 던지고 끝(fire-and-forget)이라 반환값이 없다.
 *
 * <p>v1은 no-op 스텁이다(아래 body가 로그만 남김). AI 서버가 붙으면 이 body를 실제 호출(async/짧은 타임아웃)로 교체한다.
 * 그동안은 콜백 엔드포인트를 수동 호출해 흐름을 진행한다. 무중단 운영용으로 no-op/AI를 런타임에 전환해야 하면,
 * 그때 인터페이스로 추출(Extract Interface)한다.
 */
@Slf4j
@Component
public class CardSuggestionDispatcher {

    public void dispatch(String taskId, List<SourceItemDto> sourceItems, String callbackUrl) {
        log.info("no-op card suggestion dispatch: taskId={}, sourceItems={}, callbackUrl={}",
                taskId, sourceItems == null ? 0 : sourceItems.size(), callbackUrl);
    }
}
