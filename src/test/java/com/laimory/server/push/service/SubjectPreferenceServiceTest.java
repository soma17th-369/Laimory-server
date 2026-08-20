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
 * 전체 푸시 마스터 leaf 검증 — 행 부재는 조회·쓰기 모두 던지고, worker의 batch 판정만 추정 없이
 * 결과에서 제외한다는 계약을 고정한다.
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
        SubjectPreference preference = new SubjectPreference() {
        };
        ReflectionTestUtils.setField(preference, "subjectId", SUBJECT_ID);
        ReflectionTestUtils.setField(preference, "pushEnabled", enabled);
        return preference;
    }

    @Test
    void createDefault_isEnabledWithKstAuditTime() {
        service().createDefaultIfAbsent(SUBJECT_ID);

        verify(subjectPreferenceRepository).insertIfAbsent(SUBJECT_ID.toString(), true, NOW_KST);
    }

    @Test
    void findPushEnabled_missingRow_failsLoudlyWithoutWriting() {
        // 마스터 행 부재는 깨진 불변식이다 — 기본값으로 가리면 조회가 "켜짐"이라 답하는데 예정 알림은
        // 나가지 않는다. 조회도 쓰기와 같은 운영 신호를 낸다.
        when(subjectPreferenceRepository.findById(SUBJECT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().findPushEnabled(SUBJECT_ID))
                .isInstanceOf(IllegalStateException.class);
        verify(subjectPreferenceRepository, never()).insertIfAbsent(anyString(), anyBoolean(), any());
    }

    @Test
    void findPushEnabled_presentRowIsReturnedWithoutWriting() {
        when(subjectPreferenceRepository.findById(SUBJECT_ID)).thenReturn(Optional.of(preference(false)));

        assertThat(service().findPushEnabled(SUBJECT_ID)).isFalse();

        verify(subjectPreferenceRepository, never()).insertIfAbsent(anyString(), anyBoolean(), any());
    }

    @Test
    void updatePushEnabled_whenRowMissing_failsLoudlyWithoutCreating() {
        // 쓰기 경로는 행을 만들지 않는다 — 행 존재는 가입 transaction·backfill이 보장하고, 0행은
        // 그 보장이 깨진 운영 신호다(조용한 no-op 200 금지).
        when(subjectPreferenceRepository.updatePushEnabled(SUBJECT_ID, false)).thenReturn(0);

        assertThatThrownBy(() -> service().updatePushEnabled(SUBJECT_ID, false))
                .isInstanceOf(IllegalStateException.class);
        verify(subjectPreferenceRepository, never()).insertIfAbsent(anyString(), anyBoolean(), any());
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
