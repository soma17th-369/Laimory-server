package com.laimory.server.push.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.push.NotificationConsentSource;
import com.laimory.server.push.NotificationConsentType;
import com.laimory.server.push.OptOutTokens;
import com.laimory.server.push.entity.PushRegistration;
import com.laimory.server.testsupport.TestSubjects;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 비로그인 수신거부 검증 — credential 실패의 단일 응답 수렴, 현재 owner 기준 철회, 등록 보존. 인프라 0.
 */
@ExtendWith(MockitoExtension.class)
class PushOptOutServiceTest {

    private static final UUID SUBJECT_ID = TestSubjects.id(41L);
    private static final String FID = "fid-1";
    private static final String TOKEN = token((byte) 3);
    private static final String OTHER_TOKEN = token((byte) 9);

    private static String token(byte fill) {
        byte[] raw = new byte[32];
        java.util.Arrays.fill(raw, fill);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    @Mock
    private PushRegistrationService pushRegistrationService;
    @Mock
    private NotificationConsentService notificationConsentService;

    private PushOptOutService service() {
        return new PushOptOutService(pushRegistrationService, notificationConsentService);
    }

    private static PushRegistration registration(String optOutTokenHash) {
        PushRegistration registration = new PushRegistration() {
        };
        ReflectionTestUtils.setField(registration, "subjectId", SUBJECT_ID);
        ReflectionTestUtils.setField(registration, "firebaseInstallationId", FID);
        ReflectionTestUtils.setField(registration, "optOutTokenHash", optOutTokenHash);
        return registration;
    }

    @Test
    void validCredential_withdrawsAdvertisingConsentOfCurrentOwner() {
        when(pushRegistrationService.findForOptOut(FID))
                .thenReturn(Optional.of(registration(OptOutTokens.hash(TOKEN))));

        service().optOut("v1", FID, TOKEN);

        // owner는 요청 body가 아니라 잠근 등록 행에서 해석한다.
        verify(notificationConsentService).apply(SUBJECT_ID,
                NotificationConsentType.ADVERTISING_PUSH, false, null,
                NotificationConsentSource.INSTALLATION_OPT_OUT);
    }

    @Test
    void registrationIsNeverDeleted() {
        when(pushRegistrationService.findForOptOut(FID))
                .thenReturn(Optional.of(registration(OptOutTokens.hash(TOKEN))));
        when(notificationConsentService.apply(any(), any(), anyBoolean(), any(), any()))
                .thenReturn(List.of());

        service().optOut("v1", FID, TOKEN);

        // 정보성 알림 수신과 같은 요청의 재시도가 유지돼야 한다.
        verify(pushRegistrationService, never()).unregister(any(), any(), anyString());
        verify(pushRegistrationService, never()).unregisterAllForSubject(any());
    }

    @Test
    void unknownFid_isRejectedWithSharedCredentialError() {
        when(pushRegistrationService.findForOptOut(FID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().optOut("v1", FID, TOKEN))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getExceptionType())
                                .isEqualTo(ExceptionType.PUSH_OPT_OUT_TOKEN_INVALID));
        verify(notificationConsentService, never()).apply(any(), any(), anyBoolean(), any(), any());
    }

    @Test
    void mismatchedToken_isRejectedWithSameErrorAsUnknownFid() {
        // FID 존재 여부가 응답으로 구분되면 임의의 FID로 등록 여부를 캐낼 수 있다.
        when(pushRegistrationService.findForOptOut(FID))
                .thenReturn(Optional.of(registration(OptOutTokens.hash(TOKEN))));

        assertThatThrownBy(() -> service().optOut("v1", FID, OTHER_TOKEN))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getExceptionType())
                                .isEqualTo(ExceptionType.PUSH_OPT_OUT_TOKEN_INVALID));
        verify(notificationConsentService, never()).apply(any(), any(), anyBoolean(), any(), any());
    }

    @Test
    void legacyRegistrationWithoutStoredHash_cannotOptOut() {
        when(pushRegistrationService.findForOptOut(FID)).thenReturn(Optional.of(registration(null)));

        assertThatThrownBy(() -> service().optOut("v1", FID, TOKEN))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getExceptionType())
                                .isEqualTo(ExceptionType.PUSH_OPT_OUT_TOKEN_INVALID));
    }

}
