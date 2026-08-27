package com.laimory.server.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.laimory.server.auth.service.RefreshTokenService;
import com.laimory.server.push.service.DailyNotificationPreferenceService;
import com.laimory.server.push.service.PushRegistrationService;
import com.laimory.server.push.service.SubjectPreferenceService;
import com.laimory.server.terms.service.TermAgreementService;
import com.laimory.server.timeline.repository.UserMemoryUpdatePendingStore;
import com.laimory.server.timeline.service.DailyRecordService;
import com.laimory.server.user.AccountErasureJobStatus;
import com.laimory.server.user.UserStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 계정 삭제 단계의 순서·fail-closed 계약(#302 §7.1·§7.5).
 *
 * <p>여기서 고정하는 것은 "무엇을 지우는가"가 아니라 <b>순서와 중단 조건</b>이다 — 순서가 틀리면
 * FK가 거절하고, 중단 조건이 틀리면 회원 행만 남거나 mapping만 사라진다.
 */
@ExtendWith(MockitoExtension.class)
class AccountErasureServiceTest {

    private static final long USER_ID = 42L;
    private static final long JOB_ID = 7L;
    private static final UUID SUBJECT_ID = UUID.randomUUID();

    @Mock
    private UserAccountService userAccountService;
    @Mock
    private SubjectMappingService subjectMappingService;
    @Mock
    private AccountErasureJobService accountErasureJobService;
    @Mock
    private DailyRecordService dailyRecordService;
    @Mock
    private UserMemoryUpdatePendingStore userMemoryUpdatePendingStore;
    @Mock
    private UserMemoryService userMemoryService;
    @Mock
    private DailyNotificationPreferenceService dailyNotificationPreferenceService;
    @Mock
    private SubjectPreferenceService subjectPreferenceService;
    @Mock
    private PushRegistrationService pushRegistrationService;
    @Mock
    private RefreshTokenService refreshTokenService;
    @Mock
    private TermAgreementService termAgreementService;

    @InjectMocks
    private AccountErasureService accountErasureService;

    @Test
    void 탈퇴_대기_회원만_처리_대상이다() {
        when(userAccountService.findStatus(USER_ID)).thenReturn(Optional.of(UserStatus.ACTIVE));

        assertThatThrownBy(() -> accountErasureService.resolveTarget(USER_ID))
                .isInstanceOf(IllegalStateException.class);
        verify(subjectMappingService, never()).getRequired(anyLong());
    }

    @Test
    void 회원_행이_없으면_대상을_특정하지_않는다() {
        when(userAccountService.findStatus(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountErasureService.resolveTarget(USER_ID))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 정지는_record_id_페이지를_모두_돌며_큐를_비운다() {
        when(dailyRecordService.findIdsBySubjectIdAfterId(eq(SUBJECT_ID), eq(0L), anyInt()))
                .thenReturn(List.of(1L, 2L, 3L));
        when(dailyRecordService.findIdsBySubjectIdAfterId(eq(SUBJECT_ID), eq(3L), anyInt()))
                .thenReturn(List.of(9L));
        when(dailyRecordService.findIdsBySubjectIdAfterId(eq(SUBJECT_ID), eq(9L), anyInt()))
                .thenReturn(List.of());

        accountErasureService.quiesce(SUBJECT_ID);

        verify(userMemoryUpdatePendingStore).removeAll(SUBJECT_ID, List.of(1L, 2L, 3L));
        verify(userMemoryUpdatePendingStore).removeAll(SUBJECT_ID, List.of(9L));
    }

    @Test
    void 정지는_아무것도_삭제하지_않는다() {
        when(dailyRecordService.findIdsBySubjectIdAfterId(eq(SUBJECT_ID), anyLong(), anyInt()))
                .thenReturn(List.of());

        accountErasureService.quiesce(SUBJECT_ID);

        verify(userMemoryService, never()).delete(any());
        verify(subjectPreferenceService, never()).delete(any());
        verify(subjectMappingService, never()).deleteMapping(anyLong(), any());
    }

    @Test
    void 알림_설정은_master보다_먼저_지운다() {
        accountErasureService.deleteOwnerRows(USER_ID, SUBJECT_ID);

        InOrder order = inOrder(dailyNotificationPreferenceService, subjectPreferenceService);
        order.verify(dailyNotificationPreferenceService).delete(SUBJECT_ID);
        order.verify(subjectPreferenceService).delete(SUBJECT_ID);
    }

    @Test
    void finalization은_FK없는_행_재삭제_mapping_job_user_순서다() {
        when(subjectMappingService.deleteMapping(USER_ID, SUBJECT_ID)).thenReturn(true);
        when(accountErasureJobService.deleteCompleted(JOB_ID, AccountErasureJobStatus.QUIESCED))
                .thenReturn(true);
        when(userAccountService.deleteWithdrawn(USER_ID)).thenReturn(true);

        assertThat(accountErasureService.finalizeErasure(JOB_ID, USER_ID, SUBJECT_ID)).isTrue();

        InOrder order = inOrder(refreshTokenService, pushRegistrationService, termAgreementService,
                subjectMappingService, accountErasureJobService, userAccountService);
        order.verify(refreshTokenService).deleteAllByUserId(USER_ID);
        order.verify(pushRegistrationService).deleteAll(SUBJECT_ID);
        order.verify(termAgreementService).deleteAllByUserId(USER_ID);
        order.verify(subjectMappingService).deleteMapping(USER_ID, SUBJECT_ID);
        order.verify(accountErasureJobService).deleteCompleted(JOB_ID, AccountErasureJobStatus.QUIESCED);
        order.verify(userAccountService).deleteWithdrawn(USER_ID);
    }

    @Test
    void mapping이_기대_subject와_다르면_회원_행을_지우지_않는다() {
        when(subjectMappingService.deleteMapping(USER_ID, SUBJECT_ID)).thenReturn(false);

        assertThat(accountErasureService.finalizeErasure(JOB_ID, USER_ID, SUBJECT_ID)).isFalse();

        verify(accountErasureJobService, never()).deleteCompleted(anyLong(), any());
        verify(userAccountService, never()).deleteWithdrawn(anyLong());
    }

    @Test
    void job이_이미_사라졌으면_회원_행을_지우지_않는다() {
        when(subjectMappingService.deleteMapping(USER_ID, SUBJECT_ID)).thenReturn(true);
        when(accountErasureJobService.deleteCompleted(JOB_ID, AccountErasureJobStatus.QUIESCED))
                .thenReturn(false);

        assertThat(accountErasureService.finalizeErasure(JOB_ID, USER_ID, SUBJECT_ID)).isFalse();

        verify(userAccountService, never()).deleteWithdrawn(anyLong());
    }
}
