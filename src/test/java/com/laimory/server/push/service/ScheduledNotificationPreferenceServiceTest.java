package com.laimory.server.push.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.laimory.server.push.ScheduledNotificationType;
import com.laimory.server.push.entity.ScheduledNotificationPreference;
import com.laimory.server.push.entity.ScheduledNotificationPreferenceId;
import com.laimory.server.push.repository.ScheduledNotificationPreferenceRepository;
import com.laimory.server.testsupport.TestSubjects;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
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
 * 종류별 설정 leaf 검증 — 다음 예정 시각 계산 규칙, 기본값, claim 후 전진 계약. 인프라 0.
 *
 * <p>occurrence 계산은 "하루에 한 번"과 "지연 복구가 다음 날 알림을 잡아먹지 않음"을 동시에 지켜야 해서
 * 경계 케이스를 값으로 고정한다.
 */
@ExtendWith(MockitoExtension.class)
class ScheduledNotificationPreferenceServiceTest {

    private static final UUID SUBJECT_ID = TestSubjects.id(11L);
    private static final ScheduledNotificationType TYPE = ScheduledNotificationType.DAILY_REMINDER;
    /** KST 2026-07-21 20:00. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-21T11:00:00Z"), ZoneOffset.UTC);
    private static final LocalDateTime NOW_KST = LocalDateTime.of(2026, 7, 21, 20, 0);

    @Mock
    private ScheduledNotificationPreferenceRepository repository;

    private ScheduledNotificationPreferenceService service() {
        return new ScheduledNotificationPreferenceService(repository, CLOCK);
    }

    private static ScheduledNotificationPreference preference(boolean enabled, LocalTime time,
                                                              LocalDate lastProcessed, LocalDateTime nextDueAt) {
        ScheduledNotificationPreference preference = new ScheduledNotificationPreference() {
        };
        ReflectionTestUtils.setField(preference, "id", new ScheduledNotificationPreferenceId(SUBJECT_ID, TYPE));
        ReflectionTestUtils.setField(preference, "enabled", enabled);
        ReflectionTestUtils.setField(preference, "notificationTime", time);
        ReflectionTestUtils.setField(preference, "lastProcessedOccurrenceDate", lastProcessed);
        ReflectionTestUtils.setField(preference, "nextDueAt", nextDueAt);
        return preference;
    }

    // --- 다음 예정 시각 규칙 ---

    @Test
    void nextDueAt_isTodayWhenTimeIsStillAhead() {
        assertThat(ScheduledNotificationPreferenceService.computeNextDueAt(
                NOW_KST, LocalTime.of(21, 0), null))
                .isEqualTo(LocalDateTime.of(2026, 7, 21, 21, 0));
    }

    @Test
    void nextDueAt_movesToTomorrowWhenTimeAlreadyPassed() {
        assertThat(ScheduledNotificationPreferenceService.computeNextDueAt(
                NOW_KST, LocalTime.of(19, 0), null))
                .isEqualTo(LocalDateTime.of(2026, 7, 22, 19, 0));
    }

    @Test
    void nextDueAt_movesToTomorrowWhenTodaysOccurrenceWasAlreadyProcessed() {
        // 같은 날 두 번 발송 금지 — 시각을 미래로 바꿔도 오늘 몫은 이미 소비됐다.
        assertThat(ScheduledNotificationPreferenceService.computeNextDueAt(
                NOW_KST, LocalTime.of(21, 0), LocalDate.of(2026, 7, 21)))
                .isEqualTo(LocalDateTime.of(2026, 7, 22, 21, 0));
    }

    @Test
    void nextDueAt_isTodayWhenLastProcessedIsAnEarlierDay() {
        assertThat(ScheduledNotificationPreferenceService.computeNextDueAt(
                NOW_KST, LocalTime.of(21, 0), LocalDate.of(2026, 7, 20)))
                .isEqualTo(LocalDateTime.of(2026, 7, 21, 21, 0));
    }

    @Test
    void nextDueAt_atExactlyNow_movesToTomorrow() {
        // 경계: 지금과 같은 시각은 "아직 미래"가 아니다(즉시 재발송 방지).
        assertThat(ScheduledNotificationPreferenceService.computeNextDueAt(
                NOW_KST, LocalTime.of(20, 0), null))
                .isEqualTo(LocalDateTime.of(2026, 7, 22, 20, 0));
    }

    // --- 기본값 ---

    @Test
    void createDefault_isDisabledAtNineNinePm() {
        service().createDefaultIfAbsent(SUBJECT_ID, TYPE);

        // 기본은 OFF/21:00 — 시각은 저장하되 사용자가 직접 켜기 전에는 보내지 않는다.
        verify(repository).insertIfAbsent(SUBJECT_ID.toString(), "DAILY_REMINDER", false,
                LocalTime.of(21, 0), LocalDateTime.of(2026, 7, 21, 21, 0), NOW_KST);
    }

    // --- ON/OFF·시각 변경 ---

    @Test
    void updateEnabled_recomputesNextDueFromStoredTime() {
        when(repository.findById(any())).thenReturn(Optional.of(
                preference(false, LocalTime.of(21, 30), null, LocalDateTime.of(2026, 7, 21, 21, 30))));

        service().updateEnabled(SUBJECT_ID, TYPE, true);

        verify(repository).updateEnabled(SUBJECT_ID, TYPE, true, LocalDateTime.of(2026, 7, 21, 21, 30));
    }

    @Test
    void updateNotificationTime_movesNextDueToNewTime() {
        when(repository.findById(any())).thenReturn(Optional.of(
                preference(true, LocalTime.of(21, 0), null, LocalDateTime.of(2026, 7, 21, 21, 0))));

        service().updateNotificationTime(SUBJECT_ID, TYPE, LocalTime.of(22, 15));

        verify(repository).updateNotificationTime(SUBJECT_ID, TYPE, LocalTime.of(22, 15),
                LocalDateTime.of(2026, 7, 21, 22, 15));
    }

    // --- claim ---

    @Test
    void claimDue_advancesEveryClaimedRowInSameTransaction() {
        ScheduledNotificationPreference due =
                preference(true, LocalTime.of(19, 0), null, LocalDateTime.of(2026, 7, 21, 19, 0));
        when(repository.findDueForUpdateSkipLocked("DAILY_REMINDER", NOW_KST, 250))
                .thenReturn(List.of(due));
        when(repository.markProcessedAndAdvance(eq("DAILY_REMINDER"), any(), eq(NOW_KST))).thenReturn(1);

        List<ScheduledNotificationPreference> claimed = service().claimDue(TYPE, 250);

        assertThat(claimed).containsExactly(due);
        verify(repository).markProcessedAndAdvance("DAILY_REMINDER", List.of(SUBJECT_ID.toString()), NOW_KST);
    }

    @Test
    void claimDue_noDueRows_skipsAdvanceStatement() {
        when(repository.findDueForUpdateSkipLocked(anyString(), any(), anyInt())).thenReturn(List.of());

        assertThat(service().claimDue(TYPE, 250)).isEmpty();

        verify(repository, never()).markProcessedAndAdvance(any(), any(), any());
    }

    @Test
    void claimDue_advanceCountMismatch_failsLoudly() {
        // 잠근 행 수와 전진한 행 수가 다르면 같은 occurrence가 다시 선택될 수 있다 — 조용히 넘기지 않는다.
        when(repository.findDueForUpdateSkipLocked(anyString(), any(), anyInt())).thenReturn(List.of(
                preference(true, LocalTime.of(19, 0), null, LocalDateTime.of(2026, 7, 21, 19, 0))));
        when(repository.markProcessedAndAdvance(any(), any(), any())).thenReturn(0);

        assertThatThrownBy(() -> service().claimDue(TYPE, 250))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void claimDue_rejectsOutOfRangeLimit() {
        assertThatThrownBy(() -> service().claimDue(TYPE, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service().claimDue(TYPE, 1_001)).isInstanceOf(IllegalArgumentException.class);
    }
}
