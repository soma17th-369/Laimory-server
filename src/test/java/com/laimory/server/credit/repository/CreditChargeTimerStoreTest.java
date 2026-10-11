package com.laimory.server.credit.repository;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 만료된 논리 키가 차감 타이머 키와 정확히 일치할 때만 referenceId를 꺼낸다(#565). */
class CreditChargeTimerStoreTest {

    private static final String TASK_ID = "0190a1b2-0001-7000-8000-000000000001";

    @Test
    void timerKeyWithCanonicalUuidYieldsReferenceId() {
        assertThat(CreditChargeTimerStore.referenceIdOf("credit:charge-timer:" + TASK_ID)).contains(TASK_ID);
    }

    @Test
    void lockKeyOrNonCanonicalSuffixYieldsNothing() {
        assertThat(CreditChargeTimerStore.referenceIdOf("credit:refund-lock:" + TASK_ID)).isEmpty();
        assertThat(CreditChargeTimerStore.referenceIdOf("credit:charge-timer:" + TASK_ID.toUpperCase())).isEmpty();
        assertThat(CreditChargeTimerStore.referenceIdOf("credit:charge-timer:" + TASK_ID + ":x")).isEmpty();
    }
}
