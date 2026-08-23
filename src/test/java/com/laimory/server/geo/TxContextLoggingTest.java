package com.laimory.server.geo;

import static org.assertj.core.api.Assertions.assertThat;

import com.laimory.server.common.logging.TransactionIds;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import reactor.util.context.Context;

/**
 * 로그 액션 실행 순간의 MDC 복원 계약 3케이스 — signal이 실행되는 이벤트루프 스레드에 MDC가 잔류하거나
 * 기존 값이 유실되면 무관한 요청의 로그에 tx가 오염되므로, set/원복/미접촉을 각각 고정한다.
 */
class TxContextLoggingTest {

    private static final String KEY = TransactionIds.MDC_KEY;

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void runWithTx_setsTxDuringAction_andClearsAfter_whenNoPreviousValue() {
        AtomicReference<String> seenDuringAction = new AtomicReference<>();

        TxContextLogging.runWithTx(Context.of(KEY, "tx-1"), () -> seenDuringAction.set(MDC.get(KEY)));

        assertThat(seenDuringAction.get()).isEqualTo("tx-1");
        // 기존 값이 없었으면 clear — worker(이벤트루프) 스레드에 MDC 잔류 금지.
        assertThat(MDC.get(KEY)).isNull();
    }

    @Test
    void runWithTx_restoresPreviousValue_afterAction() {
        MDC.put(KEY, "outer");
        AtomicReference<String> seenDuringAction = new AtomicReference<>();

        TxContextLogging.runWithTx(Context.of(KEY, "tx-1"), () -> seenDuringAction.set(MDC.get(KEY)));

        assertThat(seenDuringAction.get()).isEqualTo("tx-1");
        // worker 스레드에 이미 있던 다른 값은 원복된다.
        assertThat(MDC.get(KEY)).isEqualTo("outer");
    }

    @Test
    void runWithTx_leavesMdcUntouched_whenContextHasNoTransactionId() {
        MDC.put(KEY, "outer");
        AtomicReference<String> seenDuringAction = new AtomicReference<>();

        TxContextLogging.runWithTx(Context.empty(), () -> seenDuringAction.set(MDC.get(KEY)));

        // Context에 tx가 없으면 MDC를 건드리지 않는다 — 액션 중에도, 이후에도 기존 값 그대로.
        assertThat(seenDuringAction.get()).isEqualTo("outer");
        assertThat(MDC.get(KEY)).isEqualTo("outer");
    }
}
