package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** no-op 디스패처가 콜백 토큰을 로그에 남기지 않는지 검증(비밀 유출 방지). 인프라 0. */
@ExtendWith(OutputCaptureExtension.class)
class NoOpTimelineEventSuggestionDispatcherTest {

    private final NoOpTimelineEventSuggestionDispatcher dispatcher = new NoOpTimelineEventSuggestionDispatcher();

    @Test
    void dispatch_doesNotLogToken(CapturedOutput output) {
        String secretToken = "SUPER-SECRET-CALLBACK-TOKEN-zzz";

        dispatcher.dispatch("task-1", secretToken);

        assertThat(output).doesNotContain(secretToken);
        assertThat(output.getOut()).contains("task-1"); // 디스패치 로깅 자체는 일어남(sanity)
    }
}
