package com.laimory.server.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.laimory.server.timeline.service.TimelineTaskService;
import com.laimory.server.timeline.service.UserMemoryUpdateWorker;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * 계정 삭제 worker 설정의 기동 불변식(#302 §5.2·§4).
 *
 * <p>두 부등식이 이 worker 설계의 근거를 지킨다.
 * <ul>
 *   <li>{@code quiesce-delay >= max(살아 있는 AI task/presign TTL) + margin} — 정지가 살아 있는
 *       작업보다 먼저 오면 그 결과의 실패 통보가 미반영 큐를 다시 채운다. presign TTL만 환경변수로
 *       바뀔 수 있어 이 검증이 없으면 근거가 조용히 깨진다.</li>
 *   <li>{@code stale-after <= quiesce-delay} — 접수 insert가 {@code created_at}/{@code updated_at}에
 *       같은 값을 넣으므로 실효 gate가 둘 중 큰 값이 된다. 어기면 정지 시점이 조용히 늦어진다.</li>
 * </ul>
 */
class AccountErasureWorkerPropertiesTest {

    private static final Duration PRESIGN_TTL = Duration.ofMinutes(10);
    private static final Duration DEFAULT_QUIESCE_DELAY = Duration.ofMinutes(20);
    private static final Duration DEFAULT_STALE_AFTER = Duration.ofMinutes(15);

    private static AccountErasureWorkerProperties properties(Duration quiesceDelay, Duration staleAfter) {
        return properties(quiesceDelay, staleAfter, PRESIGN_TTL);
    }

    private static AccountErasureWorkerProperties properties(
            Duration quiesceDelay, Duration staleAfter, Duration presignTtl) {
        return new AccountErasureWorkerProperties(
                true, quiesceDelay, staleAfter, 7, 3, 100, 1, 10, Duration.ofSeconds(120), presignTtl);
    }

    @Test
    void 기본값은_두_부등식을_모두_만족한다() {
        assertThatCode(() -> properties(DEFAULT_QUIESCE_DELAY, DEFAULT_STALE_AFTER))
                .doesNotThrowAnyException();
    }

    @Test
    void quiesce_delay가_살아있는_작업_TTL보다_짧으면_기동에_실패한다() {
        Duration tooShort = PRESIGN_TTL; // margin이 없어 하한 미달
        assertThatThrownBy(() -> properties(tooShort, Duration.ofMinutes(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("quiesce-delay");
    }

    @Test
    void presign_TTL을_올리면_quiesce_delay_하한도_함께_올라간다() {
        Duration raisedPresignTtl = Duration.ofHours(1);
        assertThatThrownBy(() -> properties(DEFAULT_QUIESCE_DELAY, DEFAULT_STALE_AFTER, raisedPresignTtl))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("quiesce-delay");

        assertThatCode(() -> properties(
                raisedPresignTtl.plusMinutes(5), DEFAULT_STALE_AFTER, raisedPresignTtl))
                .doesNotThrowAnyException();
    }

    @Test
    void stale_after가_quiesce_delay보다_크면_기동에_실패한다() {
        assertThatThrownBy(() -> properties(DEFAULT_QUIESCE_DELAY, DEFAULT_QUIESCE_DELAY.plusMinutes(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stale-after");
    }

    @Test
    void 정지_지연_하한은_두_AI_task_TTL_상수도_함께_본다() {
        // presign을 아주 짧게 두면 하한을 정하는 것은 draft/User Memory task TTL 상수다.
        Duration taskTtlFloor = TimelineTaskService.PROCESSING_TTL.compareTo(UserMemoryUpdateWorker.TASK_TTL) >= 0
                ? TimelineTaskService.PROCESSING_TTL
                : UserMemoryUpdateWorker.TASK_TTL;
        Duration shortPresign = Duration.ofSeconds(1);

        assertThatThrownBy(() -> properties(taskTtlFloor, Duration.ofSeconds(30), shortPresign))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("quiesce-delay");
        assertThatCode(() -> properties(taskTtlFloor.plusMinutes(5), Duration.ofSeconds(30), shortPresign))
                .doesNotThrowAnyException();
    }

    @Test
    void 유예와_처리_창은_최소_하루씩이어야_한다() {
        assertThatThrownBy(() -> new AccountErasureWorkerProperties(
                true, DEFAULT_QUIESCE_DELAY, DEFAULT_STALE_AFTER, 0, 3, 100, 1, 10,
                Duration.ofSeconds(120), PRESIGN_TTL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("grace-period-days");

        assertThatThrownBy(() -> new AccountErasureWorkerProperties(
                true, DEFAULT_QUIESCE_DELAY, DEFAULT_STALE_AFTER, 7, 0, 100, 1, 10,
                Duration.ofSeconds(120), PRESIGN_TTL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("window-days");
    }

    @Test
    void 확정된_기본_정책값은_유예_7일_처리창_3일이다() {
        AccountErasureWorkerProperties properties = properties(DEFAULT_QUIESCE_DELAY, DEFAULT_STALE_AFTER);
        assertThat(properties.getGracePeriodDays()).isEqualTo(7);
        assertThat(properties.getWindowDays()).isEqualTo(3);
        assertThat(properties.isWorkerEnabled()).isTrue();
    }
}
