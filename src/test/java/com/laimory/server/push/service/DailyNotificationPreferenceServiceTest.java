package com.laimory.server.push.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.laimory.server.push.entity.DailyNotificationPreference;
import com.laimory.server.push.repository.DailyNotificationPreferenceRepository;
import com.laimory.server.testsupport.TestSubjects;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 일일 알림 설정 leaf 검증 — 다음 예정 시각 계산 규칙, 기본값, claim 후 전진 계약. 인프라 0.
 *
 * <p>occurrence 계산은 항상 "현재 이후 첫 occurrence"다 — 하루 1회 캡은 없고, 지연 복구가 다음 날
 * 알림을 잡아먹지 않는 것은 같은 규칙의 결과다. 경계 케이스를 값으로 고정한다.
 *
 * <p>시각은 서버 고정 상수라 행에서 읽지 않는다 — 쓰기 경로가 행을 조회하지 않는다는 것도 함께 고정한다.
 */
@ExtendWith(MockitoExtension.class)
class DailyNotificationPreferenceServiceTest {

    private static final UUID SUBJECT_ID = TestSubjects.id(11L);
    private static final UUID OTHER_SUBJECT_ID = TestSubjects.id(12L);
    /** KST 2026-07-21 20:00 — 고정 시각 21:00이 아직 오늘 미래다. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-21T11:00:00Z"), ZoneOffset.UTC);
    private static final LocalDateTime NOW_KST = LocalDateTime.of(2026, 7, 21, 20, 0);
    /** KST 2026-07-21 22:00 — 고정 시각이 이미 지났다. */
    private static final Clock CLOCK_AFTER = Clock.fixed(Instant.parse("2026-07-21T13:00:00Z"), ZoneOffset.UTC);

    @Mock
    private DailyNotificationPreferenceRepository repository;

    private DailyNotificationPreferenceService service() {
        return service(CLOCK);
    }

    private DailyNotificationPreferenceService service(Clock clock) {
        return new DailyNotificationPreferenceService(repository, clock);
    }

    private static DailyNotificationPreference preference(UUID subjectId, boolean enabled,
                                                          LocalDateTime nextDueAt) {
        DailyNotificationPreference preference = new DailyNotificationPreference() {
        };
        ReflectionTestUtils.setField(preference, "subjectId", subjectId);
        ReflectionTestUtils.setField(preference, "enabled", enabled);
        ReflectionTestUtils.setField(preference, "nextDueAt", nextDueAt);
        return preference;
    }

    // --- 다음 예정 시각 규칙 ---

    @Test
    void notificationTime_isServerFixedAtNinePm() {
        // 시각의 권위는 DB가 아니라 이 상수다 — 사용자 입력도 운영 SQL도 바꾸지 않는다.
        assertThat(DailyNotificationPreferenceService.NOTIFICATION_TIME).isEqualTo(LocalTime.of(21, 0));
    }

    @Test
    void nextDueAt_isTodayWhenFixedTimeIsStillAhead() {
        assertThat(DailyNotificationPreferenceService.computeNextDueAt(NOW_KST))
                .isEqualTo(LocalDateTime.of(2026, 7, 21, 21, 0));
    }

    @Test
    void nextDueAt_movesToTomorrowWhenFixedTimeAlreadyPassed() {
        assertThat(DailyNotificationPreferenceService.computeNextDueAt(
                LocalDateTime.of(2026, 7, 21, 22, 0)))
                .isEqualTo(LocalDateTime.of(2026, 7, 22, 21, 0));
    }

    @Test
    void nextDueAt_atExactlyTheFixedTime_movesToTomorrow() {
        // 경계: 지금과 같은 시각은 "아직 미래"가 아니다(즉시 재발송 방지).
        assertThat(DailyNotificationPreferenceService.computeNextDueAt(
                LocalDateTime.of(2026, 7, 21, 21, 0)))
                .isEqualTo(LocalDateTime.of(2026, 7, 22, 21, 0));
    }

    // --- 기본값 ---

    @Test
    void findSettings_missingRow_failsLoudlyWithoutWriting() {
        // 기본값으로 가리면 "켜짐"이라 답하면서 실제로는 아무것도 보내지 않는다(worker는 없는 행을
        // claim하지 못한다). 조회도 쓰기와 같은 운영 신호를 내야 세 경로가 한 방향을 가리킨다.
        when(repository.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().findSettings(SUBJECT_ID))
                .isInstanceOf(IllegalStateException.class);
        verify(repository, never()).insertIfAbsent(any(), anyBoolean(), any(), any());
    }

    @Test
    void findSettings_returnsTheServerFixedTime() {
        when(repository.findById(SUBJECT_ID))
                .thenReturn(Optional.of(preference(SUBJECT_ID, true, LocalDateTime.of(2026, 7, 21, 21, 0))));

        assertThat(service().findSettings(SUBJECT_ID))
                .isEqualTo(new DailyNotificationPreferenceService.Settings(true, LocalTime.of(21, 0)));
    }

    @Test
    void createDefault_isEnabledAtNinePm() {
        service().createDefaultIfAbsent(SUBJECT_ID);

        // 기본은 ON이고 첫 occurrence는 고정 시각 21:00(#318) — 가입만으로 발송 대상이 되고 사용자는 끄기만 한다.
        verify(repository).insertIfAbsent(
                SUBJECT_ID.toString(), true, LocalDateTime.of(2026, 7, 21, 21, 0), NOW_KST);
    }

    // --- ON/OFF 전환 ---

    @Test
    void updateEnabled_rearmsNextDueAtWithoutReadingTheRow() {
        // 켜는 순간 재장전하지 않으면, 꺼둔 사이 과거로 굳은 next_due_at이 허용 지연 안쪽이라
        // 켠 직후 tick이 예정에 없던 알림을 보낸다. 시각이 고정이라 그 값을 알아내려 행을 읽지 않는다.
        when(repository.updateEnabled(any(), anyBoolean(), any())).thenReturn(1);

        service().updateEnabled(SUBJECT_ID, true);

        verify(repository).updateEnabled(SUBJECT_ID, true, LocalDateTime.of(2026, 7, 21, 21, 0));
        verify(repository, never()).findById(any());
        verify(repository, never()).insertIfAbsent(any(), anyBoolean(), any(), any());
    }

    @Test
    void updateEnabled_whenFixedTimeAlreadyPassed_rearmsToTomorrow() {
        when(repository.updateEnabled(any(), anyBoolean(), any())).thenReturn(1);

        service(CLOCK_AFTER).updateEnabled(SUBJECT_ID, true);

        verify(repository).updateEnabled(SUBJECT_ID, true, LocalDateTime.of(2026, 7, 22, 21, 0));
    }

    @Test
    void updateEnabled_whenUpdateAffectsNoRow_failsLoudlyWithoutCreating() {
        // 쓰기 경로는 행을 만들지 않는다 — 행 존재는 가입 transaction·backfill이 보장하고, 0행은
        // 그 보장이 깨진 운영 신호다(조용한 no-op 200 금지).
        when(repository.updateEnabled(any(), anyBoolean(), any())).thenReturn(0);

        assertThatThrownBy(() -> service().updateEnabled(SUBJECT_ID, true))
                .isInstanceOf(IllegalStateException.class);
        verify(repository, never()).insertIfAbsent(any(), anyBoolean(), any(), any());
    }

    // --- claim ---

    @Test
    void claimDue_advancesEveryClaimedRowInSameTransaction() {
        DailyNotificationPreference due =
                preference(SUBJECT_ID, true, LocalDateTime.of(2026, 7, 21, 19, 0));
        when(repository.findDueForUpdateSkipLocked(NOW_KST, 250)).thenReturn(List.of(due));
        when(repository.advanceNextDueAt(any(), any())).thenReturn(1);

        List<DailyNotificationPreference> claimed = service().claimDue(250);

        assertThat(claimed).containsExactly(due);
        // 전진 값은 Java가 KST로 계산해 넘긴다 — 고정 시각 21:00은 아직 오늘 미래다.
        verify(repository).advanceNextDueAt(List.of(SUBJECT_ID), LocalDateTime.of(2026, 7, 21, 21, 0));
    }

    @Test
    void claimDue_advancesAllClaimedRowsInOneStatement() {
        // 시각이 서버 고정이라 claim된 행 전부가 같은 다음 예정 시각을 갖는다 — 문장이 하나로 수렴한다.
        when(repository.findDueForUpdateSkipLocked(any(), anyInt())).thenReturn(List.of(
                preference(SUBJECT_ID, true, LocalDateTime.of(2026, 7, 21, 19, 0)),
                preference(OTHER_SUBJECT_ID, true, LocalDateTime.of(2026, 7, 20, 21, 0))));
        when(repository.advanceNextDueAt(any(), any())).thenReturn(2);

        service().claimDue(250);

        verify(repository).advanceNextDueAt(
                List.of(SUBJECT_ID, OTHER_SUBJECT_ID), LocalDateTime.of(2026, 7, 21, 21, 0));
    }

    @Test
    void claimDue_noDueRows_skipsAdvanceStatement() {
        when(repository.findDueForUpdateSkipLocked(any(), anyInt())).thenReturn(List.of());

        assertThat(service().claimDue(250)).isEmpty();

        verify(repository, never()).advanceNextDueAt(any(), any());
    }

    @Test
    void claimDue_advanceCountMismatch_failsLoudly() {
        // 잠근 행 수와 전진한 행 수가 다르면 같은 occurrence가 다시 선택될 수 있다 — 조용히 넘기지 않는다.
        when(repository.findDueForUpdateSkipLocked(any(), anyInt())).thenReturn(List.of(
                preference(SUBJECT_ID, true, LocalDateTime.of(2026, 7, 21, 19, 0))));
        when(repository.advanceNextDueAt(any(), any())).thenReturn(0);

        assertThatThrownBy(() -> service().claimDue(250))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void claimDue_rejectsOutOfRangeLimit() {
        assertThatThrownBy(() -> service().claimDue(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service().claimDue(1_001)).isInstanceOf(IllegalArgumentException.class);
    }
}
