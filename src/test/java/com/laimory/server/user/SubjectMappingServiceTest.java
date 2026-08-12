package com.laimory.server.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.laimory.server.common.id.SubjectId;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * SubjectMappingService 계약: getRequired는 current→(rotation 기간 한정) previous 순서로 조회하고,
 * 누락은 자동 생성·raw userId fallback 없이 fail-closed(메시지 무식별자)한다. previous hit는 PK·version을
 * current로 원자 교체하되 subject는 보존한다. createFor는 새 UUIDv4 subject를 current key로 insert한다.
 * createIfAbsent(#285 backfill 전용)는 current(rotation 기간엔 previous 포함) 존재 확인 후 없을 때만
 * 생성하는 멱등 경로다.
 */
@ExtendWith(MockitoExtension.class)
class SubjectMappingServiceTest {

    private static final long USER_ID = 777L;
    private static final short CURRENT_VERSION = 3;

    @Mock
    private UserSubjectLinkRepository userSubjectLinkRepository;

    @Mock
    private SubjectLookupKeyDeriver subjectLookupKeyDeriver;

    @Mock
    private SubjectMappingMetrics subjectMappingMetrics;

    @InjectMocks
    private SubjectMappingService subjectMappingService;

    private static byte[] lookupKey(int seed) {
        byte[] key = new byte[32];
        key[0] = (byte) seed;
        return key;
    }

    @Test
    void createFor_insertsNewSubjectWithCurrentKeyAndVersion() {
        when(subjectLookupKeyDeriver.deriveCurrent(USER_ID)).thenReturn(lookupKey(1));
        when(subjectLookupKeyDeriver.currentVersion()).thenReturn(CURRENT_VERSION);

        subjectMappingService.createFor(USER_ID);

        ArgumentCaptor<UserSubjectLink> captor = ArgumentCaptor.forClass(UserSubjectLink.class);
        verify(userSubjectLinkRepository).saveAndFlush(captor.capture());
        UserSubjectLink saved = captor.getValue();
        assertThat(saved.getUserLookupKey()).isEqualTo(lookupKey(1));
        assertThat(saved.getLookupKeyVersion()).isEqualTo(CURRENT_VERSION);
        assertThat(saved.getSubjectId()).hasSize(16);
        assertThat((saved.getSubjectId()[6] >> 4) & 0x0F).isEqualTo(4); // UUIDv4
    }

    @Test
    void createIfAbsent_missingMapping_insertsNewSubjectAndReturnsTrue() {
        when(subjectLookupKeyDeriver.deriveCurrent(USER_ID)).thenReturn(lookupKey(1));
        when(subjectLookupKeyDeriver.derivePrevious(USER_ID)).thenReturn(Optional.empty());
        when(subjectLookupKeyDeriver.currentVersion()).thenReturn(CURRENT_VERSION);
        when(userSubjectLinkRepository.findById(lookupKey(1))).thenReturn(Optional.empty());

        boolean created = subjectMappingService.createIfAbsent(USER_ID);

        assertThat(created).isTrue();
        ArgumentCaptor<UserSubjectLink> captor = ArgumentCaptor.forClass(UserSubjectLink.class);
        verify(userSubjectLinkRepository).saveAndFlush(captor.capture());
        UserSubjectLink saved = captor.getValue();
        assertThat(saved.getUserLookupKey()).isEqualTo(lookupKey(1));
        assertThat(saved.getLookupKeyVersion()).isEqualTo(CURRENT_VERSION);
        assertThat(saved.getSubjectId()).hasSize(16);
        assertThat((saved.getSubjectId()[6] >> 4) & 0x0F).isEqualTo(4); // UUIDv4
    }

    @Test
    void createIfAbsent_currentKeyPresent_isNoOpAndReturnsFalse() {
        when(subjectLookupKeyDeriver.deriveCurrent(USER_ID)).thenReturn(lookupKey(1));
        when(userSubjectLinkRepository.findById(lookupKey(1)))
                .thenReturn(Optional.of(UserSubjectLink.of(
                        lookupKey(1), SubjectId.newRandom().bytes(), CURRENT_VERSION)));

        assertThat(subjectMappingService.createIfAbsent(USER_ID)).isFalse();

        verify(userSubjectLinkRepository, never()).saveAndFlush(any());
    }

    @Test
    void createIfAbsent_previousKeyPresentDuringRotation_isNoOpWithoutSecondSubject() {
        // rotation 기간에 previous key 행이 있는 사용자에게 두 번째 subject를 만들면 안 된다 —
        // 그 행의 current key 교체는 getRequired의 rekey 경로가 담당한다.
        when(subjectLookupKeyDeriver.deriveCurrent(USER_ID)).thenReturn(lookupKey(1));
        when(subjectLookupKeyDeriver.derivePrevious(USER_ID)).thenReturn(Optional.of(lookupKey(2)));
        when(userSubjectLinkRepository.findById(lookupKey(1))).thenReturn(Optional.empty());
        when(userSubjectLinkRepository.findById(lookupKey(2)))
                .thenReturn(Optional.of(UserSubjectLink.of(
                        lookupKey(2), SubjectId.newRandom().bytes(), (short) 1)));

        assertThat(subjectMappingService.createIfAbsent(USER_ID)).isFalse();

        verify(userSubjectLinkRepository, never()).saveAndFlush(any());
        verify(userSubjectLinkRepository, never()).rekey(any(), any(), anyShort());
    }

    @Test
    void getRequired_currentHit_returnsSubjectWithoutRekey() {
        SubjectId subject = SubjectId.newRandom();
        when(subjectLookupKeyDeriver.deriveCurrent(USER_ID)).thenReturn(lookupKey(1));
        when(userSubjectLinkRepository.findById(lookupKey(1)))
                .thenReturn(Optional.of(UserSubjectLink.of(lookupKey(1), subject.bytes(), CURRENT_VERSION)));

        SubjectId result = subjectMappingService.getRequired(USER_ID);

        assertThat(result).isEqualTo(subject);
        verify(userSubjectLinkRepository, never()).rekey(any(), any(), anyShort());
        verify(userSubjectLinkRepository, never()).saveAndFlush(any());
    }

    @Test
    void getRequired_missWithoutPreviousKey_failsClosedWithoutIdentifiers() {
        when(subjectLookupKeyDeriver.deriveCurrent(USER_ID)).thenReturn(lookupKey(1));
        when(userSubjectLinkRepository.findById(lookupKey(1))).thenReturn(Optional.empty());
        when(subjectLookupKeyDeriver.derivePrevious(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> subjectMappingService.getRequired(USER_ID))
                .isInstanceOf(IllegalStateException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(String.valueOf(USER_ID)));
        // 자동 생성·fallback 금지 — mapping을 만들지 않는다.
        verify(userSubjectLinkRepository, never()).saveAndFlush(any());
    }

    @Test
    void getRequired_currentMissPreviousHit_rekeysAtomicallyAndPreservesSubject() {
        SubjectId subject = SubjectId.newRandom();
        when(subjectLookupKeyDeriver.deriveCurrent(USER_ID)).thenReturn(lookupKey(1));
        when(subjectLookupKeyDeriver.derivePrevious(USER_ID)).thenReturn(Optional.of(lookupKey(2)));
        when(subjectLookupKeyDeriver.currentVersion()).thenReturn(CURRENT_VERSION);
        when(userSubjectLinkRepository.findById(lookupKey(1))).thenReturn(Optional.empty());
        when(userSubjectLinkRepository.findById(lookupKey(2)))
                .thenReturn(Optional.of(UserSubjectLink.of(lookupKey(2), subject.bytes(), (short) 1)));
        when(userSubjectLinkRepository.rekey(lookupKey(2), lookupKey(1), CURRENT_VERSION)).thenReturn(1);

        SubjectId result = subjectMappingService.getRequired(USER_ID);

        assertThat(result).isEqualTo(subject); // 교체는 PK·version만 — subject 불변
        verify(userSubjectLinkRepository).rekey(lookupKey(2), lookupKey(1), CURRENT_VERSION);
        verify(userSubjectLinkRepository, never()).saveAndFlush(any());
    }

    @Test
    void getRequired_rekeyRace_zeroAffectedRows_stillReturnsSubject() {
        SubjectId subject = SubjectId.newRandom();
        when(subjectLookupKeyDeriver.deriveCurrent(USER_ID)).thenReturn(lookupKey(1));
        when(subjectLookupKeyDeriver.derivePrevious(USER_ID)).thenReturn(Optional.of(lookupKey(2)));
        when(subjectLookupKeyDeriver.currentVersion()).thenReturn(CURRENT_VERSION);
        when(userSubjectLinkRepository.findById(lookupKey(1))).thenReturn(Optional.empty());
        when(userSubjectLinkRepository.findById(lookupKey(2)))
                .thenReturn(Optional.of(UserSubjectLink.of(lookupKey(2), subject.bytes(), (short) 1)));
        when(userSubjectLinkRepository.rekey(lookupKey(2), lookupKey(1), CURRENT_VERSION)).thenReturn(0);

        // 동시 getRequired가 먼저 교체(0행)해도 subject는 동일 — 멱등으로 성공한다.
        assertThat(subjectMappingService.getRequired(USER_ID)).isEqualTo(subject);
    }

    @Test
    void getRequired_currentAndPreviousMiss_failsClosed() {
        when(subjectLookupKeyDeriver.deriveCurrent(USER_ID)).thenReturn(lookupKey(1));
        when(subjectLookupKeyDeriver.derivePrevious(USER_ID)).thenReturn(Optional.of(lookupKey(2)));
        when(userSubjectLinkRepository.findById(any(byte[].class))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> subjectMappingService.getRequired(USER_ID))
                .isInstanceOf(IllegalStateException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(String.valueOf(USER_ID)));
    }
}
