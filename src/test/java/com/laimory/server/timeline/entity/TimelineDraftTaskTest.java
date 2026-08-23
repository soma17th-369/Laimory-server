package com.laimory.server.timeline.entity;

import static com.laimory.server.testsupport.TestSubjects.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.laimory.server.timeline.ProcessStage;
import com.laimory.server.timeline.TaskTokens;
import com.laimory.server.timeline.entity.TimelineDraftTask.RetryReceipt;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** retry receipt의 lifecycle 불변식을 고정한다 — 잘못된 조합은 저장되기 전에 터져야 한다. */
class TimelineDraftTaskTest {

    private static final UUID SUBJECT_ID = id(7L);
    private static final long RECORD_ID = 42L;
    private static final Instant STARTED_AT = Instant.parse("2026-06-17T03:05:00Z");
    private static final Instant NOW = Instant.parse("2026-06-17T03:10:00Z");
    private static final Instant UNTIL = NOW.plusSeconds(15);
    private static final String TOKEN = "raw-task-token";
    private static final String TOKEN_HASH = TaskTokens.hash(TOKEN);

    private TimelineDraftTask taskAt(ProcessStage stage) {
        return TimelineDraftTask.processing(SUBJECT_ID, RECORD_ID, null, TOKEN_HASH, STARTED_AT)
                .withTokenAndStage(TOKEN_HASH, stage);
    }

    private RetryReceipt claim() {
        return new RetryReceipt(TOKEN_HASH, NOW, UNTIL);
    }

    @Test
    void retryReceipt_onTerminalTask_isRejected() {
        TimelineDraftTask terminal = TimelineDraftTask.success(SUBJECT_ID, RECORD_ID, TOKEN_HASH);

        assertThatThrownBy(() -> terminal.withRetryReceipt(claim()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void withTokenAndStage_preservesReceipt() {
        // 재시도 재발급이 같은 stage에서 token만 다시 돌리므로 보존이 필요하다.
        TimelineDraftTask claimed = taskAt(ProcessStage.RESULT_PENDING).withRetryReceipt(claim());

        TimelineDraftTask rotated =
                claimed.withTokenAndStage(TaskTokens.hash("next"), ProcessStage.CALLBACK_PENDING);

        assertThat(rotated.retryReceipt()).isEqualTo(claim());
    }

    @Test
    void terminalTransitions_dropReceipt() {
        // receipt가 사라지므로 callback 이후의 결과 재요청은 인지 대상이 아니다(401로 수렴).
        assertThat(TimelineDraftTask.success(SUBJECT_ID, RECORD_ID, TOKEN_HASH).retryReceipt()).isNull();
        assertThat(TimelineDraftTask.failed(SUBJECT_ID, RECORD_ID, -1008, TOKEN_HASH).retryReceipt()).isNull();
    }

    @Test
    void retryReceipt_requiresTokenHashAndDeadline() {
        assertThatThrownBy(() -> new RetryReceipt(null, NOW, UNTIL))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RetryReceipt(TOKEN_HASH, NOW, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void matchesPreviousToken_withoutReceipt_isFalse() {
        assertThat(taskAt(ProcessStage.RESULT_PENDING).matchesPreviousToken(TOKEN)).isFalse();
    }

    @Test
    void matchesPreviousToken_withReceipt_comparesConsumedTokenHash() {
        TimelineDraftTask claimed = taskAt(ProcessStage.RESULT_PENDING).withRetryReceipt(claim());

        assertThat(claimed.matchesPreviousToken(TOKEN)).isTrue();
        assertThat(claimed.matchesPreviousToken("other")).isFalse();
    }

    @Test
    void retryWindowExpired_boundaryIsInclusive_andNoReceiptCountsAsExpired() {
        TimelineDraftTask claimed = taskAt(ProcessStage.RESULT_PENDING).withRetryReceipt(claim());

        assertThat(claimed.retryWindowExpired(UNTIL.minusMillis(1))).isFalse();
        assertThat(claimed.retryWindowExpired(UNTIL)).isTrue();
        assertThat(taskAt(ProcessStage.RESULT_PENDING).retryWindowExpired(NOW)).isTrue();
    }
}
