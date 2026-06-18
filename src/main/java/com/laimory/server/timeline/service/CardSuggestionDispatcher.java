package com.laimory.server.timeline.service;

import com.laimory.server.timeline.dto.SourceItemDto;
import java.util.List;

/**
 * 카드 제안(Card Suggestion) 생성을 외부 AI에 위임하는 포트.
 *
 * <p>POST 흐름은 이 디스패치를 절대 블로킹하지 않는다(요청 스레드가 LLM 응답을 기다리면 톰캣 풀이 고갈됨).
 * 실제 구현은 비동기/짧은 타임아웃으로 호출만 트리거하고, 결과는 AI가 {@code callbackUrl}로 콜백한다.
 * v1은 {@link NoOpCardSuggestionDispatcher}로 아무것도 하지 않는다(콜백 수동 호출로 테스트).
 */
public interface CardSuggestionDispatcher {

    void dispatch(String taskId, List<SourceItemDto> sourceItems, String callbackUrl);
}
