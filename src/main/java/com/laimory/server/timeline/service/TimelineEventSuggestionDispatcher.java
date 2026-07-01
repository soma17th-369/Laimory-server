package com.laimory.server.timeline.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 타임라인 이벤트 제안 생성을 외부 AI에 위임한다.
 *
 * <p>POST 흐름은 이 디스패치를 절대 블로킹하지 않는다(요청 스레드가 LLM 응답을 기다리면 톰캣 풀이 고갈됨).
 * source item은 POST 시점에 MySQL {@code timeline_draft_source_items}에 저장돼 있어, AI는 taskId로 DB에서 직접 읽는다 —
 * dispatch는 sourceItems를 넘기지 않는다. AI는 콜백 주소를 이미 알고 있으므로 callbackUrl도 동적으로 넘기지 않는다.
 * AI는 콜백 시 {@code callbackToken}을 {@code Callback-Token} 헤더로 되돌려준다.
 * {@code dispatch}는 던지고 끝(fire-and-forget)이라 반환값이 없다.
 *
 * <p><b>결과 반환 계약(write-then-notify)</b>: AI는 결과를 콜백 바디로 보내지 않는다. 이벤트 메타는
 * {@code timeline_draft_event_suggestions}에 INSERT하고, 각 이벤트에 묶이는 source item은
 * {@code timeline_draft_source_items.timeline_draft_event_suggestion_id}를 UPDATE해 가리킨다(한 트랜잭션으로 commit).
 * 그 뒤 콜백은 {@code {status, error}}만 보낸다(taskId는 URL path). 결과를 DB에 먼저 영속시켜 API가 콜백 처리 중
 * 죽어도 유실되지 않게 하고, AI는 2xx를 받을 때까지 콜백을 재시도한다(at-least-once + API 멱등).
 * ⚠️ <b>task별 콜백은 순차</b>다 — 같은 task는 직전 콜백의 응답(또는 타임아웃)을 받은 뒤에만 다음 재시도를 보내고
 * 병렬로 동시 전송하지 않는다(동시 finalize로 인한 이벤트 중복 방지의 전제).
 *
 * <p>v1은 no-op 스텁이다(아래 body가 taskId만 로그). AI 서버가 붙으면 이 body를 실제 호출(async/짧은 타임아웃)로 교체한다.
 * ⚠️ {@code callbackToken}은 비밀이므로 절대 로그하지 않는다(실제 구현은 헤더로만 전송).
 */
@Slf4j
@Component
public class TimelineEventSuggestionDispatcher {

    public void dispatch(String taskId, String callbackToken) {
        // callbackToken은 의도적으로 로그에서 제외한다.
        log.info("no-op timeline event suggestion dispatch: taskId={}", taskId);
    }
}
