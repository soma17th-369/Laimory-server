package com.laimory.server.geo;

import com.laimory.server.common.logging.TransactionIds;
import org.slf4j.MDC;
import reactor.util.context.ContextView;

/**
 * Reactor signal 실행 시점에 transactionId MDC를 복원해 로그를 남기는 헬퍼.
 *
 * <p>Reactor 체인의 signal(onNext/onError)은 조립 스레드가 아니라 방출 스레드(Netty 이벤트루프 등)에서
 * 실행되므로 서블릿 스레드의 MDC가 보이지 않는다. blocking 경계({@link GeocodingService})가 구독 시점에
 * Reactor Context로 실어 보낸 transactionId를 <b>로그 액션 실행 순간에만</b> worker 스레드 MDC에 넣었다가
 * finally에서 원복한다 — 이벤트루프 스레드에 MDC가 잔류하면 무관한 요청의 로그에 tx가 새므로 금지.
 *
 * <p>Context에 transactionId가 없으면(비요청 경로 등) MDC를 건드리지 않고 그대로 실행한다.
 */
final class TxContextLogging {

    private TxContextLogging() {
    }

    /** {@code logAction}을 transactionId MDC가 복원된 상태로 실행한다. 기존 MDC 값은 실행 후 원복/제거한다. */
    static void runWithTx(ContextView context, Runnable logAction) {
        String transactionId = context.getOrDefault(TransactionIds.MDC_KEY, null);
        if (transactionId == null) {
            logAction.run();
            return;
        }
        String previous = MDC.get(TransactionIds.MDC_KEY);
        MDC.put(TransactionIds.MDC_KEY, transactionId);
        try {
            logAction.run();
        } finally {
            if (previous != null) {
                MDC.put(TransactionIds.MDC_KEY, previous);
            } else {
                MDC.remove(TransactionIds.MDC_KEY);
            }
        }
    }
}
