package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.laimory.server.timeline.ItemType;
import com.laimory.server.timeline.dto.SourceItemDto;
import com.laimory.server.timeline.payload.PhotoPayload;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** no-op 디스패처가 콜백 토큰을 로그에 남기지 않는지 검증(비밀 유출 방지). 인프라 0. */
@ExtendWith(OutputCaptureExtension.class)
class CardSuggestionDispatcherTest {

    private final CardSuggestionDispatcher dispatcher = new CardSuggestionDispatcher();

    @Test
    void dispatch_doesNotLogToken(CapturedOutput output) {
        String secretToken = "SUPER-SECRET-CALLBACK-TOKEN-zzz";
        List<SourceItemDto> sources = List.of(new SourceItemDto(0, ItemType.PHOTO,
                LocalDateTime.of(2026, 6, 17, 9, 0), null, "s", new PhotoPayload("u", 1.0, 2.0)));

        dispatcher.dispatch("task-1", secretToken, sources, "http://localhost:8080/cb");

        assertThat(output).doesNotContain(secretToken);
        assertThat(output.getOut()).contains("task-1"); // 디스패치 로깅 자체는 일어남(sanity)
    }
}
