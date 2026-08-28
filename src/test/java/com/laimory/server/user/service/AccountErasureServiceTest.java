package com.laimory.server.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
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
import com.laimory.server.timeline.photo.PhotoObjectKeys;
import com.laimory.server.timeline.photo.S3PhotoStorageService;
import com.laimory.server.timeline.repository.UserMemoryUpdatePendingStore;
import com.laimory.server.timeline.service.TimelineContentErasureService;
import com.laimory.server.timeline.service.DailyRecordService;
import com.laimory.server.user.AccountErasureJobStatus;
import com.laimory.server.user.UserStatus;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private TimelineContentErasureService timelineContentErasureService;
    @Mock
    private S3PhotoStorageService s3PhotoStorageService;
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

        accountErasureService.finalizeErasure(JOB_ID, USER_ID, SUBJECT_ID);

        InOrder order = inOrder(refreshTokenService, pushRegistrationService, termAgreementService,
                subjectMappingService, accountErasureJobService, userAccountService);
        order.verify(refreshTokenService).deleteAllByUserId(USER_ID);
        order.verify(pushRegistrationService).deleteAll(SUBJECT_ID);
        order.verify(termAgreementService).deleteAllByUserId(USER_ID);
        order.verify(subjectMappingService).deleteMapping(USER_ID, SUBJECT_ID);
        order.verify(accountErasureJobService).deleteCompleted(JOB_ID, AccountErasureJobStatus.QUIESCED);
        order.verify(userAccountService).deleteWithdrawn(USER_ID);
    }

    /**
     * 예상 밖 0행은 <b>예외로</b> 보고해야 한다. boolean으로 되돌려주면 Spring이 rollback하지 않아
     * 그때까지의 DELETE가 commit되고 반쪽 상태가 남는다.
     */
    @Test
    void mapping이_기대_subject와_다르면_예외로_rollback시킨다() {
        when(subjectMappingService.deleteMapping(USER_ID, SUBJECT_ID)).thenReturn(false);

        assertThatThrownBy(() -> accountErasureService.finalizeErasure(JOB_ID, USER_ID, SUBJECT_ID))
                .isInstanceOf(AccountErasureConflictException.class);

        verify(accountErasureJobService, never()).deleteCompleted(anyLong(), any());
        verify(userAccountService, never()).deleteWithdrawn(anyLong());
    }

    @Test
    void job이_이미_사라졌으면_예외로_rollback시킨다() {
        when(subjectMappingService.deleteMapping(USER_ID, SUBJECT_ID)).thenReturn(true);
        when(accountErasureJobService.deleteCompleted(JOB_ID, AccountErasureJobStatus.QUIESCED))
                .thenReturn(false);

        assertThatThrownBy(() -> accountErasureService.finalizeErasure(JOB_ID, USER_ID, SUBJECT_ID))
                .isInstanceOf(AccountErasureConflictException.class);

        verify(userAccountService, never()).deleteWithdrawn(anyLong());
    }

    @Test
    void S3는_prefix가_빌_때까지_반복해_지운다() {
        String prefix = PhotoObjectKeys.subjectNamespace(SUBJECT_ID) + "/photos/";
        List<S3PhotoStorageService.ObjectVersion> page1 = List.of(
                new S3PhotoStorageService.ObjectVersion("a", "null"),
                new S3PhotoStorageService.ObjectVersion("b", "null"));
        List<S3PhotoStorageService.ObjectVersion> page2 =
                List.of(new S3PhotoStorageService.ObjectVersion("c", "null"));
        when(s3PhotoStorageService.listObjectVersions(eq(prefix), anyInt()))
                .thenReturn(page1).thenReturn(page2).thenReturn(List.of());
        when(s3PhotoStorageService.deleteVersions(page1)).thenReturn(
                new S3PhotoStorageService.BatchDeleteResult(Set.of("a@null", "b@null"), Map.of(), Set.of()));
        when(s3PhotoStorageService.deleteVersions(page2)).thenReturn(
                new S3PhotoStorageService.BatchDeleteResult(Set.of("c@null"), Map.of(), Set.of()));

        assertThat(accountErasureService.deletePhotoObjects(SUBJECT_ID, () -> true)).isTrue();

        // 마지막 재조회가 비었을 때만 끝난다 — 3회 조회.
        verify(s3PhotoStorageService, org.mockito.Mockito.times(3)).listObjectVersions(eq(prefix), anyInt());
    }

    @Test
    void 삭제가_확인되지_않은_S3_객체가_있으면_멈춘다() {
        List<S3PhotoStorageService.ObjectVersion> page = List.of(
                new S3PhotoStorageService.ObjectVersion("a", "null"),
                new S3PhotoStorageService.ObjectVersion("b", "null"));
        when(s3PhotoStorageService.listObjectVersions(anyString(), anyInt())).thenReturn(page);
        when(s3PhotoStorageService.deleteVersions(page))
                .thenReturn(new S3PhotoStorageService.BatchDeleteResult(
                        Set.of("a@null"), Map.of("b", "AccessDenied"), Set.of()));

        assertThatThrownBy(() -> accountErasureService.deletePhotoObjects(SUBJECT_ID, () -> true))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 콘텐츠_graph는_job_record_draft_순서로_비운다() {
        when(timelineContentErasureService.deletePhotoDeleteJobBatch(eq(SUBJECT_ID), anyInt()))
                .thenReturn(2).thenReturn(0);
        when(timelineContentErasureService.deleteRecordBatch(eq(SUBJECT_ID), anyInt()))
                .thenReturn(1).thenReturn(0);
        when(timelineContentErasureService.deleteDraftSourceBatch(eq(SUBJECT_ID), anyInt()))
                .thenReturn(0);

        assertThat(accountErasureService.deleteContentGraph(SUBJECT_ID, () -> true)).isTrue();

        InOrder order = inOrder(timelineContentErasureService);
        order.verify(timelineContentErasureService, org.mockito.Mockito.atLeastOnce())
                .deletePhotoDeleteJobBatch(eq(SUBJECT_ID), anyInt());
        order.verify(timelineContentErasureService, org.mockito.Mockito.atLeastOnce())
                .deleteRecordBatch(eq(SUBJECT_ID), anyInt());
        order.verify(timelineContentErasureService, org.mockito.Mockito.atLeastOnce())
                .deleteDraftSourceBatch(eq(SUBJECT_ID), anyInt());
    }

    @Test
    void 실행_예산이_끝나면_남은_일을_다음_실행에_넘긴다() {
        when(timelineContentErasureService.deletePhotoDeleteJobBatch(eq(SUBJECT_ID), anyInt()))
                .thenReturn(1);

        assertThat(accountErasureService.deleteContentGraph(SUBJECT_ID, () -> false)).isFalse();

        // 예산이 없으니 다음 단계로 넘어가지 않는다.
        verify(timelineContentErasureService, never()).deleteRecordBatch(any(), anyInt());
    }

    /** 마지막 단계라 특히 중요하다 — 여기서 조용히 넘어가면 job이 사라진 뒤 회원 행만 영구히 남는다. */
    @Test
    void 회원_행이_지워지지_않으면_예외로_rollback시킨다() {
        when(subjectMappingService.deleteMapping(USER_ID, SUBJECT_ID)).thenReturn(true);
        when(accountErasureJobService.deleteCompleted(JOB_ID, AccountErasureJobStatus.QUIESCED))
                .thenReturn(true);
        when(userAccountService.deleteWithdrawn(USER_ID)).thenReturn(false);

        assertThatThrownBy(() -> accountErasureService.finalizeErasure(JOB_ID, USER_ID, SUBJECT_ID))
                .isInstanceOf(AccountErasureConflictException.class);
    }
}
