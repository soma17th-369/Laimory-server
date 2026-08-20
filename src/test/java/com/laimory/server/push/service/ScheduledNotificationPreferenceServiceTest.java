package com.laimory.server.push.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
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
 * <p>occurrence 계산은 항상 "현재 이후 첫 occurrence"다 — 하루 1회 캡은 없고, 지연 복구가 다음 날
 * 알림을 잡아먹지 않는 것은 같은 규칙의 결과다. 경계 케이스를 값으로 고정한다.
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
                                                              LocalDateTime nextDueAt) {
        ScheduledNotificationPreference preference = new ScheduledNotificationPreference() {
        };
        ReflectionTestUtils.setField(preference, "id", new ScheduledNotificationPreferenceId(SUBJECT_ID, TYPE));
        ReflectionTestUtils.setField(preference, "enabled", enabled);
        ReflectionTestUtils.setField(preference, "notificationTime", time);
        ReflectionTestUtils.setField(preference, "nextDueAt", nextDueAt);
        return preference;
    }

    // --- 다음 예정 시각 규칙 ---

    @Test
    void nextDueAt_isTodayWhenTimeIsStillAhead() {
        assertThat(ScheduledNotificationPreferenceService.computeNextDueAt(
                NOW_KST, LocalTime.of(21, 0)))
                .isEqualTo(LocalDateTime.of(2026, 7, 21, 21, 0));
    }

    @Test
    void nextDueAt_movesToTomorrowWhenTimeAlreadyPassed() {
        assertThat(ScheduledNotificationPreferenceService.computeNextDueAt(
                NOW_KST, LocalTime.of(19, 0)))
                .isEqualTo(LocalDateTime.of(2026, 7, 22, 19, 0));
    }

    @Test
    void nextDueAt_atExactlyNow_movesToTomorrow() {
        // 경계: 지금과 같은 시각은 "아직 미래"가 아니다(즉시 재발송 방지).
        assertThat(ScheduledNotificationPreferenceService.computeNextDueAt(
                NOW_KST, LocalTime.of(20, 0)))
                .isEqualTo(LocalDateTime.of(2026, 7, 22, 20, 0));
    }

    // --- 기본값 ---

    @Test
    void findSettings_missingRow_failsLoudlyWithoutWriting() {
        // 기본값으로 가리면 "켜짐"이라 답하면서 실제로는 아무것도 보내지 않는다(worker는 없는 행을
        // claim하지 못한다). 조회도 쓰기와 같은 운영 신호를 내야 세 경로가 한 방향을 가리킨다.
        when(repository.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().findSettings(SUBJECT_ID, TYPE))
                .isInstanceOf(IllegalStateException.class);
        verify(repository, never()).insertIfAbsent(any(), any(), anyBoolean(), any(), any(), any());
    }

    @Test
    void createDefault_isEnabledAtNinePm() {
        service().createDefaultIfAbsent(SUBJECT_ID, TYPE);

        // 기본은 ON/21:00(#318) — 가입만으로 매일 21:00 발송 대상이 되고 사용자는 끄기만 한다.
        verify(repository).insertIfAbsent(SUBJECT_ID.toString(), "DAILY_REMINDER", true,
                LocalTime.of(21, 0), LocalDateTime.of(2026, 7, 21, 21, 0), NOW_KST);
    }

    // --- ON/OFF 전환 ---

    @Test
    void updateEnabled_rearmsNextDueAtFromTheStoredTime() {
        // 켜는 순간 재장전하지 않으면, 꺼둔 사이 과거로 굳은 next_due_at이 허용 지연 안쪽이라
        // 켠 직후 tick이 예정에 없던 알림을 보낸다. 저장된 시각(21:00)은 오늘 아직 미래이므로 오늘로.
        when(repository.findById(any()))
                .thenReturn(Optional.of(preference(false, LocalTime.of(21, 0),
                        LocalDateTime.of(2026, 7, 20, 21, 0))));
        when(repository.updateEnabled(any(), any(), anyBoolean(), any())).thenReturn(1);

        service().updateEnabled(SUBJECT_ID, TYPE, true);

        verify(repository).updateEnabled(SUBJECT_ID, TYPE, true, LocalDateTime.of(2026, 7, 21, 21, 0));
        verify(repository, never()).insertIfAbsent(any(), any(), anyBoolean(), any(), any(), any());
    }

    @Test
    void updateEnabled_whenStoredTimeAlreadyPassed_rearmsToTomorrow() {
        when(repository.findById(any()))
                .thenReturn(Optional.of(preference(false, LocalTime.of(19, 0),
                        LocalDateTime.of(2026, 7, 20, 19, 0))));
        when(repository.updateEnabled(any(), any(), anyBoolean(), any())).thenReturn(1);

        service().updateEnabled(SUBJECT_ID, TYPE, true);

        verify(repository).updateEnabled(SUBJECT_ID, TYPE, true, LocalDateTime.of(2026, 7, 22, 19, 0));
    }

    @Test
    void updateEnabled_whenRowMissing_failsLoudlyWithoutCreating() {
        // 쓰기 경로는 행을 만들지 않는다 — 행 존재는 가입 transaction·backfill이 보장하고, 부재는
        // 그 보장이 깨진 운영 신호다(조용한 no-op 200 금지).
        when(repository.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().updateEnabled(SUBJECT_ID, TYPE, true))
                .isInstanceOf(IllegalStateException.class);
        verify(repository, never()).insertIfAbsent(any(), any(), anyBoolean(), any(), any(), any());
        verify(repository, never()).updateEnabled(any(), any(), anyBoolean(), any());
    }

    @Test
    void updateEnabled_whenUpdateAffectsNoRow_failsLoudly() {
        // 읽은 뒤 사라진 행(탈퇴 등) — 조용히 성공으로 끝내지 않는다.
        when(repository.findById(any()))
                .thenReturn(Optional.of(preference(false, LocalTime.of(21, 0),
                        LocalDateTime.of(2026, 7, 20, 21, 0))));
        when(repository.updateEnabled(any(), any(), anyBoolean(), any())).thenReturn(0);

        assertThatThrownBy(() -> service().updateEnabled(SUBJECT_ID, TYPE, true))
                .isInstanceOf(IllegalStateException.class);
    }

    // --- claim ---

    @Test
    void claimDue_advancesEveryClaimedRowInSameTransaction() {
        ScheduledNotificationPreference due =
                preference(true, LocalTime.of(19, 0), LocalDateTime.of(2026, 7, 21, 19, 0));
        when(repository.findDueForUpdateSkipLocked("DAILY_REMINDER", NOW_KST, 250))
                .thenReturn(List.of(due));
        when(repository.advanceNextDueAt(any(), any(), any())).thenReturn(1);

        List<ScheduledNotificationPreference> claimed = service().claimDue(TYPE, 250);

        assertThat(claimed).containsExactly(due);
        // 전진 값은 Java가 KST로 계산해 넘긴다 — 19:00은 이미 지났으니 다음 날 같은 시각.
        verify(repository).advanceNextDueAt(TYPE, List.of(SUBJECT_ID),
                LocalDateTime.of(2026, 7, 22, 19, 0));
    }

    @Test
    void claimDue_groupsRowsByTheirOwnAdvanceValues() {
        // 시각이 다른 행은 전진 값도 달라야 한다 — 한 문장으로 뭉뚱그리면 남의 시각으로 덮인다.
        ScheduledNotificationPreference nine = preference(true, LocalTime.of(19, 0),
                LocalDateTime.of(2026, 7, 21, 19, 0));
        ScheduledNotificationPreference eight = preference(true, LocalTime.of(18, 0),
                LocalDateTime.of(2026, 7, 21, 18, 0));
        when(repository.findDueForUpdateSkipLocked(anyString(), any(), anyInt()))
                .thenReturn(List.of(nine, eight));
        when(repository.advanceNextDueAt(any(), any(), any())).thenReturn(1);

        service().claimDue(TYPE, 250);

        verify(repository).advanceNextDueAt(TYPE, List.of(SUBJECT_ID),
                LocalDateTime.of(2026, 7, 22, 19, 0));
        verify(repository).advanceNextDueAt(TYPE, List.of(SUBJECT_ID),
                LocalDateTime.of(2026, 7, 22, 18, 0));
    }

    @Test
    void claimDue_noDueRows_skipsAdvanceStatement() {
        when(repository.findDueForUpdateSkipLocked(anyString(), any(), anyInt())).thenReturn(List.of());

        assertThat(service().claimDue(TYPE, 250)).isEmpty();

        verify(repository, never()).advanceNextDueAt(any(), any(), any());
    }

    @Test
    void claimDue_advanceCountMismatch_failsLoudly() {
        // 잠근 행 수와 전진한 행 수가 다르면 같은 occurrence가 다시 선택될 수 있다 — 조용히 넘기지 않는다.
        when(repository.findDueForUpdateSkipLocked(anyString(), any(), anyInt())).thenReturn(List.of(
                preference(true, LocalTime.of(19, 0), LocalDateTime.of(2026, 7, 21, 19, 0))));
        when(repository.advanceNextDueAt(any(), any(), any())).thenReturn(0);

        assertThatThrownBy(() -> service().claimDue(TYPE, 250))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void claimDue_rejectsOutOfRangeLimit() {
        assertThatThrownBy(() -> service().claimDue(TYPE, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service().claimDue(TYPE, 1_001)).isInstanceOf(IllegalArgumentException.class);
    }
}
