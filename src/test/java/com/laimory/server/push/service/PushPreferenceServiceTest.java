package com.laimory.server.push.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.laimory.server.push.PushMetrics;
import com.laimory.server.push.entity.PushPreference;
import com.laimory.server.push.repository.PushPreferenceRepository;
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
 * 전체 푸시 마스터 leaf 검증 — 행 부재 해석이 경로마다 다르다는 계약을 고정한다.
 * 기존 정보성 푸시는 rollout 공백에서 ON으로 읽되 관측·보정하고, worker의 batch 판정은 추정하지 않는다.
 */
@ExtendWith(MockitoExtension.class)
class PushPreferenceServiceTest {

    private static final UUID SUBJECT_ID = TestSubjects.id(81L);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-21T05:00:00Z"), ZoneOffset.UTC);
    private static final LocalDateTime NOW_KST = LocalDateTime.of(2026, 7, 21, 14, 0);

    @Mock
    private PushPreferenceRepository pushPreferenceRepository;
    @Mock
    private PushMetrics pushMetrics;

    private PushPreferenceService service() {
        return new PushPreferenceService(pushPreferenceRepository, pushMetrics, CLOCK);
    }

    private static PushPreference preference(boolean enabled) {
        PushPreference preference = new PushPreference() {
        };
        ReflectionTestUtils.setField(preference, "subjectId", SUBJECT_ID);
        ReflectionTestUtils.setField(preference, "pushEnabled", enabled);
        return preference;
    }

    @Test
    void createDefault_isEnabledWithKstAuditTime() {
        service().createDefaultIfAbsent(SUBJECT_ID);

        verify(pushPreferenceRepository).insertIfAbsent(SUBJECT_ID.toString(), true, NOW_KST);
    }

    @Test
    void legacyPath_missingRowReadsAsEnabled_andIsObserved() {
        when(pushPreferenceRepository.findById(SUBJECT_ID)).thenReturn(Optional.empty());

        assertThat(service().isPushEnabledForLegacyCompatibility(SUBJECT_ID)).isTrue();

        verify(pushMetrics).recordPreferenceMissing();
        // 조회는 쓰기를 하지 않는다 — 누락은 관측만 하고 복구는 backfill과 첫 설정 변경이 맡는다.
        verify(pushPreferenceRepository, never()).insertIfAbsent(anyString(), anyBoolean(), any());
    }

    @Test
    void legacyPath_presentRowIsNeitherObservedNorWritten() {
        when(pushPreferenceRepository.findById(SUBJECT_ID)).thenReturn(Optional.of(preference(false)));

        assertThat(service().isPushEnabledForLegacyCompatibility(SUBJECT_ID)).isFalse();

        verify(pushMetrics, never()).recordPreferenceMissing();
        verify(pushPreferenceRepository, never()).insertIfAbsent(anyString(), anyBoolean(), any());
    }

    @Test
    void legacyPath_lookupFailureIsNotHiddenAsEnabled() {
        // 조회 장애를 ON으로 숨기면 발송 여부가 조용히 뒤집힌다 — 호출자의 기존 실패 격리로 넘긴다.
        when(pushPreferenceRepository.findById(SUBJECT_ID)).thenThrow(new RuntimeException("db down"));

        assertThatThrownBy(() -> service().isPushEnabledForLegacyCompatibility(SUBJECT_ID))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void updatePushEnabled_whenRowMissing_failsLoudlyWithoutCreating() {
        // 쓰기 경로는 행을 만들지 않는다 — 행 존재는 가입 transaction·backfill이 보장하고, 0행은
        // 그 보장이 깨진 운영 신호다(조용한 no-op 200 금지).
        when(pushPreferenceRepository.updatePushEnabled(SUBJECT_ID, false)).thenReturn(0);

        assertThatThrownBy(() -> service().updatePushEnabled(SUBJECT_ID, false))
                .isInstanceOf(IllegalStateException.class);
        verify(pushPreferenceRepository, never()).insertIfAbsent(anyString(), anyBoolean(), any());
    }

    @Test
    void batchLookup_omitsSubjectsWithoutRow() {
        // worker는 결과에 없는 subject를 마스터 누락으로 다뤄 발송에서 제외한다(추정 금지).
        when(pushPreferenceRepository.findAllBySubjectIdIn(any())).thenReturn(List.of(preference(true)));

        Map<UUID, Boolean> result = service().findPushEnabledBySubjectIds(
                List.of(SUBJECT_ID, TestSubjects.id(82L)));

        assertThat(result).containsExactly(Map.entry(SUBJECT_ID, true));
    }

    @Test
    void batchLookup_emptyInputSkipsQuery() {
        assertThat(service().findPushEnabledBySubjectIds(List.of())).isEmpty();

        verify(pushPreferenceRepository, never()).findAllBySubjectIdIn(any());
    }
}
