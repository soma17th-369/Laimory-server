package com.laimory.server.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.laimory.server.timeline.TaskStatus;
import org.junit.jupiter.api.Test;

/**
 * 알림 문구 계약 — 문구는 코드가 소유하며 DB·운영 설정으로 바꾸지 않는다. 같은 종류라도 terminal
 * 상태에 따라 다른 문구를 써야 하는 경우가 있어 선택 책임은 {@link PushMessage} factory에 있다.
 */
class PushMessageTypeTest {

    @Test
    void timelineCompletionCopyDependsOnTerminalStatus() {
        PushMessage success = PushMessage.timelineCompletion("t-1", TaskStatus.SUCCESS);
        PushMessage failed = PushMessage.timelineCompletion("t-1", TaskStatus.FAILED);

        assertThat(success.title()).isEqualTo("타임라인 생성 완료");
        assertThat(success.body()).isEqualTo("타임라인이 준비됐어요.");
        assertThat(failed.title()).isEqualTo("타임라인 생성 실패");
        assertThat(failed.body()).isEqualTo("타임라인을 만들지 못했어요. 앱에서 다시 시도해 주세요.");
        // 문구는 달라도 metric 차원(종류)은 같다.
        assertThat(success.type()).isEqualTo(PushMessageType.TIMELINE_COMPLETION);
        assertThat(failed.type()).isEqualTo(PushMessageType.TIMELINE_COMPLETION);
    }

    @Test
    void timelineCompletionRejectsNonTerminalStatus() {
        assertThatThrownBy(() -> PushMessage.timelineCompletion("t-1", TaskStatus.PROCESSING))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void dailyReminderCopyIsFixed() {
        PushMessage message = PushMessage.dailyReminder();

        assertThat(message.type()).isEqualTo(PushMessageType.DAILY_REMINDER);
        assertThat(message.title()).isEqualTo("타임라인을 완성해보세요!");
        assertThat(message.body()).isEqualTo("하루를 기록해보세요!");
        assertThat(message.data()).containsOnlyKeys("route");
    }

    @Test
    void scheduledTypeResolvesToItsMessage() {
        assertThat(ScheduledNotificationType.DAILY_REMINDER.pushMessageType())
                .isEqualTo(PushMessageType.DAILY_REMINDER);
    }
}
