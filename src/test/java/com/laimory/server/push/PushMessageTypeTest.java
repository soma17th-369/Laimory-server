package com.laimory.server.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.laimory.server.timeline.TaskStatus;
import org.junit.jupiter.api.Test;

/**
 * 알림 문구·metric 계열 계약 — 문구는 코드가 소유하며 DB·운영 설정으로 바꾸지 않는다. 문구가 다르면
 * 종류를 나누되, 운영이 한 알림으로 보는 단위는 {@code metricGroup}이 유지한다.
 */
class PushMessageTypeTest {

    @Test
    void timelineCompletionMapsTerminalStatusToItsOwnType() {
        PushMessage success = PushMessage.timelineCompletion("t-1", TaskStatus.SUCCESS);
        PushMessage failed = PushMessage.timelineCompletion("t-1", TaskStatus.FAILED);

        assertThat(success.type()).isEqualTo(PushMessageType.TIMELINE_COMPLETION_SUCCESS);
        assertThat(failed.type()).isEqualTo(PushMessageType.TIMELINE_COMPLETION_FAILED);
        assertThat(success.data()).containsOnlyKeys("taskId", "status");
        assertThat(success.data()).containsEntry("status", "SUCCESS");
        assertThat(failed.data()).containsEntry("status", "FAILED");
    }

    @Test
    void timelineCompletionCopyDependsOnTerminalStatus() {
        // 실패에 성공 문구가 나가면 사용자가 결과를 오해한다. 종류가 나뉘어 있어 문구를 고를 여지가 없다.
        assertThat(PushMessageType.TIMELINE_COMPLETION_SUCCESS.title()).isEqualTo("타임라인 생성 완료");
        assertThat(PushMessageType.TIMELINE_COMPLETION_SUCCESS.body()).isEqualTo("타임라인이 준비됐어요.");
        assertThat(PushMessageType.TIMELINE_COMPLETION_FAILED.title()).isEqualTo("타임라인 생성 실패");
        assertThat(PushMessageType.TIMELINE_COMPLETION_FAILED.body())
                .isEqualTo("타임라인을 만들지 못했어요. 앱에서 다시 시도해 주세요.");
    }

    @Test
    void splitCompletionTypesShareOneMetricGroup() {
        // 문구 때문에 나눈 종류라 metric 차원까지 나뉘면 안 된다 — 대시보드에서 한 알림으로 읽혀야 한다.
        assertThat(PushMessageType.TIMELINE_COMPLETION_SUCCESS.metricGroup())
                .isEqualTo(PushMessageType.TIMELINE_COMPLETION_FAILED.metricGroup())
                .isEqualTo("TIMELINE_COMPLETION");
        assertThat(PushMessageType.DAILY_REMINDER.metricGroup()).isEqualTo("DAILY_REMINDER");
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
        assertThat(message.type().title()).isEqualTo("타임라인을 완성해보세요!");
        assertThat(message.type().body()).isEqualTo("하루를 기록해보세요!");
        assertThat(message.data()).containsOnlyKeys("route");
    }

    @Test
    void scheduledTypeResolvesToItsMessage() {
        assertThat(ScheduledNotificationType.DAILY_REMINDER.pushMessageType())
                .isEqualTo(PushMessageType.DAILY_REMINDER);
    }
}
