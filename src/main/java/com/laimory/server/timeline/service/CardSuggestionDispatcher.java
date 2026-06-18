package com.laimory.server.timeline.service;

import com.laimory.server.timeline.dto.SourceItemDto;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 카드 제안(Card Suggestion) 생성을 외부 AI에 위임한다.
 *
 * <p>POST 흐름은 이 디스패치를 절대 블로킹하지 않는다(요청 스레드가 LLM 응답을 기다리면 톰캣 풀이 고갈됨).
 * 결과는 AI가 {@code callbackUrl}로 콜백하며, 그때 {@code callbackToken}을 {@code Callback-Token} 헤더로 되돌려준다.
 * {@code dispatch}는 던지고 끝(fire-and-forget)이라 반환값이 없다.
 *
 * <p>v1은 no-op 스텁이다(아래 body가 로그만 남김). AI 서버가 붙으면 이 body를 실제 호출(async/짧은 타임아웃)로 교체한다.
 * ⚠️ {@code callbackToken}은 비밀이므로 절대 로그하지 않는다(실제 구현은 헤더로만 전송).
 */
@Slf4j
@Component
public class CardSuggestionDispatcher {

    public void dispatch(String taskId, String callbackToken,
                         List<SourceItemDto> sourceItems, String callbackUrl) {
        // callbackToken은 의도적으로 로그에서 제외한다.
        log.info("no-op card suggestion dispatch: taskId={}, sourceItems={}, callbackUrl={}",
                taskId, sourceItems == null ? 0 : sourceItems.size(), callbackUrl);
    }
}
