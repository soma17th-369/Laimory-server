package com.laimory.server.aidispatch;

/**
 * 대기열 메시지 하나를 처리하고 메시지를 어떻게 할지 돌려준다(#515). {@link DispatchQueueWorker}가 결과대로 메시지를
 * 지우거나 다시 받게 한다. 접수 상태·보상은 구현(도메인 쪽)이 맡고 worker는 메시지 수명만 다룬다.
 */
@FunctionalInterface
public interface DispatchMessageHandler {

    Result handle(ReceivedDispatch message);

    enum Result {
        /** 처리가 끝났다(접수·종결·중복 판정 포함) — 메시지를 지운다. */
        DONE,
        /** 접수 전에 실패했다 — 잠시 뒤 다시 받는다. 반복되면 dead-letter로 간다. */
        RETRY_LATER,
        /** 해석할 수 없는 메시지다 — 건드리지 않아 visibility 만료 뒤 재전달되다가 dead-letter로 간다(포렌식 보존). */
        LEAVE_FOR_DEAD_LETTER
    }
}
