package com.laimory.server.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.laimory.server.timeline.TaskStatus;
import java.time.LocalDate;
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
        PushCopy success = PushMessage.timelineCompletion("t-1", TaskStatus.SUCCESS).copy();
        PushCopy failed = PushMessage.timelineCompletion("t-1", TaskStatus.FAILED).copy();

        assertThat(success.title()).isEqualTo("타임라인 생성 완료");
        assertThat(success.body()).isEqualTo("타임라인이 준비됐어요.");
        assertThat(failed.title()).isEqualTo("타임라인 생성 실패");
        assertThat(failed.body()).isEqualTo("타임라인을 만들지 못했어요. 앱에서 다시 시도해 주세요.");
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
    void dailyReminderRotatesCopyByDateInDeclaredOrder() {
        // 2026-10-26은 epochDay(20752)가 4의 배수라 순환의 첫 문구가 나오는 날이다.
        LocalDate first = LocalDate.of(2026, 10, 26);

        assertThat(PushMessage.dailyReminder(first).copy())
                .isEqualTo(new PushCopy("오늘 하루 어땠나요?", "오늘의 기록으로 타임라인을 만들어보세요."));
        assertThat(PushMessage.dailyReminder(first.plusDays(1)).copy())
                .isEqualTo(new PushCopy("하루를 마무리할 시간이에요", "오늘 하루를 타임라인으로 정리해보세요."));
        assertThat(PushMessage.dailyReminder(first.plusDays(2)).copy())
                .isEqualTo(new PushCopy("오늘을 잊기 전에", "오늘 있었던 일을 타임라인으로 남겨보세요."));
        assertThat(PushMessage.dailyReminder(first.plusDays(3)).copy())
                .isEqualTo(new PushCopy("오늘의 순간들을 모아볼까요?", "흩어진 기록을 타임라인으로 만들어보세요."));
        assertThat(PushMessage.dailyReminder(first.plusDays(4)).copy())
                .isEqualTo(PushMessage.dailyReminder(first).copy());
    }

    @Test
    void dailyReminderNeverRepeatsCopyOnConsecutiveDays() {
        LocalDate start = LocalDate.of(2026, 12, 25);

        // 연말·연초를 걸쳐 epochDay 경계에서도 이틀 연속 같은 문구가 나가지 않는다.
        for (int day = 0; day < 14; day++) {
            assertThat(PushMessage.dailyReminder(start.plusDays(day + 1)).copy())
                    .isNotEqualTo(PushMessage.dailyReminder(start.plusDays(day)).copy());
        }
    }

    @Test
    void dailyReminderCarriesRoutingDataOnly() {
        PushMessage message = PushMessage.dailyReminder(LocalDate.of(2026, 10, 25));

        assertThat(message.type()).isEqualTo(PushMessageType.DAILY_REMINDER);
        assertThat(message.data()).containsOnlyKeys("route");
    }
}
