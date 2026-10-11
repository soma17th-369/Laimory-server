package com.laimory.server.timeline.repository;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 만료된 논리 키가 유실 타이머 키와 정확히 일치할 때만 taskId를 꺼낸다(#565). */
class TimelineTaskLossTimerStoreTest {

    private static final String TASK_ID = "0190a1b2-0001-7000-8000-000000000001";

    @Test
    void timerKeyWithCanonicalUuidYieldsTaskId() {
        assertThat(TimelineTaskLossTimerStore.taskIdOf("timeline:draft-task:loss-timer:" + TASK_ID)).contains(TASK_ID);
    }

    @Test
    void taskOrLockKeyOrNonCanonicalSuffixYieldsNothing() {
        // 같은 timeline:draft-task: 아래의 task key·잠금 key 만료는 유실 처리 대상이 아니다.
        assertThat(TimelineTaskLossTimerStore.taskIdOf("timeline:draft-task:" + TASK_ID)).isEmpty();
        assertThat(TimelineTaskLossTimerStore.taskIdOf("timeline:draft-task:loss-lock:" + TASK_ID)).isEmpty();
        assertThat(TimelineTaskLossTimerStore.taskIdOf(
                "timeline:draft-task:loss-timer:" + TASK_ID.toUpperCase())).isEmpty();
        assertThat(TimelineTaskLossTimerStore.taskIdOf("timeline:draft-task:loss-timer:" + TASK_ID + ":x")).isEmpty();
    }
}
