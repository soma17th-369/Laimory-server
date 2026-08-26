package com.laimory.server.push.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.laimory.server.push.entity.SubjectPreference;
import com.laimory.server.push.repository.SubjectPreferenceRepository;
import com.laimory.server.testsupport.TestSubjects;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * subject 축 설정 leaf 검증 — 마스터와 온보딩 완료 여부 모두 행 부재는 조회·쓰기에서 던지고, worker의
 * batch 판정만 추정 없이 결과에서 제외한다는 계약을 고정한다.
 */
@ExtendWith(MockitoExtension.class)
class SubjectPreferenceServiceTest {

    private static final UUID SUBJECT_ID = TestSubjects.id(81L);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-21T05:00:00Z"), ZoneOffset.UTC);
    private static final LocalDateTime NOW_KST = LocalDateTime.of(2026, 7, 21, 14, 0);

    @Mock
    private SubjectPreferenceRepository subjectPreferenceRepository;

    private SubjectPreferenceService service() {
        return new SubjectPreferenceService(subjectPreferenceRepository, CLOCK);
    }

    private static SubjectPreference preference(boolean enabled) {
        return preference(enabled, false);
    }

    private static SubjectPreference preference(boolean enabled, boolean onboardingCompleted) {
        SubjectPreference preference = new SubjectPreference() {
        };
        ReflectionTestUtils.setField(preference, "subjectId", SUBJECT_ID);
        ReflectionTestUtils.setField(preference, "pushEnabled", enabled);
        ReflectionTestUtils.setField(preference, "onboardingCompleted", onboardingCompleted);
        return preference;
    }

    @Test
    void createDefault_isEnabledAndNotOnboardedWithKstAuditTime() {
        // 두 기본값을 INSERT에 명시한다 — DB default에만 맡기면 기본값 권위가 코드와 스키마로 갈린다.
        service().createDefaultIfAbsent(SUBJECT_ID);

        verify(subjectPreferenceRepository).insertIfAbsent(SUBJECT_ID.toString(), true, false, NOW_KST);
    }

    @Test
    void findPushEnabled_missingRow_failsLoudlyWithoutWriting() {
        // 마스터 행 부재는 깨진 불변식이다 — 기본값으로 가리면 조회가 "켜짐"이라 답하는데 예정 알림은
        // 나가지 않는다. 조회도 쓰기와 같은 운영 신호를 낸다.
        when(subjectPreferenceRepository.findById(SUBJECT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().findPushEnabled(SUBJECT_ID))
                .isInstanceOf(IllegalStateException.class);
        verify(subjectPreferenceRepository, never()).insertIfAbsent(anyString(), anyBoolean(), anyBoolean(), any());
    }

    @Test
    void findPushEnabled_presentRowIsReturnedWithoutWriting() {
        when(subjectPreferenceRepository.findById(SUBJECT_ID)).thenReturn(Optional.of(preference(false)));

        assertThat(service().findPushEnabled(SUBJECT_ID)).isFalse();

        verify(subjectPreferenceRepository, never()).insertIfAbsent(anyString(), anyBoolean(), anyBoolean(), any());
    }

    @Test
    void updatePushEnabled_whenRowMissing_failsLoudlyWithoutCreating() {
        // 쓰기 경로는 행을 만들지 않는다 — 행 존재는 가입 transaction·backfill이 보장하고, 0행은
        // 그 보장이 깨진 운영 신호다(조용한 no-op 200 금지).
        when(subjectPreferenceRepository.updatePushEnabled(SUBJECT_ID, false)).thenReturn(0);

        assertThatThrownBy(() -> service().updatePushEnabled(SUBJECT_ID, false))
                .isInstanceOf(IllegalStateException.class);
        verify(subjectPreferenceRepository, never()).insertIfAbsent(anyString(), anyBoolean(), anyBoolean(), any());
    }

    @Test
    void findOnboardingCompleted_returnsStoredValueWithoutWriting() {
        // 저장값이 권위다 — 약관 동의 이력이나 기록 존재 여부로 계산하지 않고, 조회가 값을 바꾸지 않는다.
        when(subjectPreferenceRepository.findById(SUBJECT_ID))
                .thenReturn(Optional.of(preference(true, true)));

        assertThat(service().findOnboardingCompleted(SUBJECT_ID)).isTrue();

        verify(subjectPreferenceRepository, never()).insertIfAbsent(anyString(), anyBoolean(), anyBoolean(), any());
        verify(subjectPreferenceRepository, never()).markOnboardingCompleted(any());
    }

    @Test
    void findOnboardingCompleted_defaultRowIsFalse() {
        when(subjectPreferenceRepository.findById(SUBJECT_ID))
                .thenReturn(Optional.of(preference(true, false)));

        assertThat(service().findOnboardingCompleted(SUBJECT_ID)).isFalse();
    }

    @Test
    void findOnboardingCompleted_missingRow_failsLoudlyWithoutWriting() {
        // 행 부재를 false로 가리면 앱이 온보딩을 다시 태우고 그 완료 요청은 다시 0행으로 실패한다 —
        // 조회도 마스터와 같은 운영 신호를 낸다.
        when(subjectPreferenceRepository.findById(SUBJECT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().findOnboardingCompleted(SUBJECT_ID))
                .isInstanceOf(IllegalStateException.class);
        verify(subjectPreferenceRepository, never()).insertIfAbsent(anyString(), anyBoolean(), anyBoolean(), any());
    }

    @Test
    void completeOnboarding_matchedRowSucceeds() {
        // 이미 true인 행도 matched row 1이라 반복 호출이 멱등 성공한다(changed 기준이면 깨진다).
        when(subjectPreferenceRepository.markOnboardingCompleted(SUBJECT_ID)).thenReturn(1);

        service().completeOnboarding(SUBJECT_ID);

        verify(subjectPreferenceRepository).markOnboardingCompleted(SUBJECT_ID);
    }

    @Test
    void completeOnboarding_whenRowMissing_failsLoudlyWithoutCreating() {
        // 0행은 값이 같아서가 아니라 행이 없다는 뜻이다 — 쓰기 경로는 행을 만들지 않는다.
        when(subjectPreferenceRepository.markOnboardingCompleted(SUBJECT_ID)).thenReturn(0);

        assertThatThrownBy(() -> service().completeOnboarding(SUBJECT_ID))
                .isInstanceOf(IllegalStateException.class);
        verify(subjectPreferenceRepository, never()).insertIfAbsent(anyString(), anyBoolean(), anyBoolean(), any());
    }

    @Test
    void completeOnboarding_doesNotTouchPushMaster() {
        // 온보딩 완료는 알림 설정을 건드리지 않는다(컬럼 단위 UPDATE).
        when(subjectPreferenceRepository.markOnboardingCompleted(SUBJECT_ID)).thenReturn(1);

        service().completeOnboarding(SUBJECT_ID);

        verify(subjectPreferenceRepository, never()).updatePushEnabled(any(), anyBoolean());
    }

    @Test
    void batchLookup_omitsSubjectsWithoutRow() {
        // worker는 결과에 없는 subject를 마스터 누락으로 다뤄 발송에서 제외한다(추정 금지).
        when(subjectPreferenceRepository.findAllBySubjectIdIn(any())).thenReturn(List.of(preference(true)));

        Map<UUID, Boolean> result = service().findPushEnabledBySubjectIds(
                List.of(SUBJECT_ID, TestSubjects.id(82L)));

        assertThat(result).containsExactly(Map.entry(SUBJECT_ID, true));
    }

    @Test
    void batchLookup_emptyInputSkipsQuery() {
        assertThat(service().findPushEnabledBySubjectIds(List.of())).isEmpty();

        verify(subjectPreferenceRepository, never()).findAllBySubjectIdIn(any());
    }
}
