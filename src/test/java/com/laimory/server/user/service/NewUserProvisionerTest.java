package com.laimory.server.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

import com.laimory.server.push.ScheduledNotificationType;
import com.laimory.server.push.service.NotificationConsentService;
import com.laimory.server.push.service.PushPreferenceService;
import com.laimory.server.push.service.ScheduledNotificationPreferenceService;
import com.laimory.server.user.Provider;
import com.laimory.server.user.entity.User;
import com.laimory.server.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.UUID;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * provision 계약: users insert(flush)로 userId를 확보한 뒤 같은 흐름에서 subject mapping과 subject 축
 * 기본 설정 행(푸시 마스터·일일 리마인더·수신 동의)을 만든다. 어느 단계의 실패든 그대로 전파된다 —
 * 한 transaction rollback으로 user까지 사라지는 것은 @Transactional의 몫이며 통합 테스트가 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class NewUserProvisionerTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private SubjectMappingService subjectMappingService;

    @Mock
    private PushPreferenceService pushPreferenceService;

    @Mock
    private ScheduledNotificationPreferenceService scheduledNotificationPreferenceService;

    @Mock
    private NotificationConsentService notificationConsentService;

    @InjectMocks
    private NewUserProvisioner newUserProvisioner;

    private static final UUID SUBJECT_ID = UUID.fromString("0b6d4ba1-6c66-4f77-9a3e-0c0f0f2f1b21");

    private static User savedUser(long userId) {
        User user = User.of(Provider.GOOGLE, "sub-123", "e@x.com", "nick");
        ReflectionTestUtils.setField(user, "userId", userId); // IDENTITY 채번 결과 재현
        return user;
    }

    @Test
    void provision_savesUserThenCreatesMappingWithGeneratedId() {
        User saved = savedUser(42L);
        when(userRepository.saveAndFlush(any())).thenReturn(saved);
        when(subjectMappingService.createFor(42L)).thenReturn(SUBJECT_ID);

        User result = newUserProvisioner.provision(Provider.GOOGLE, "sub-123", "e@x.com", "nick");

        assertThat(result).isSameAs(saved);
        InOrder inOrder = inOrder(userRepository, subjectMappingService, pushPreferenceService,
                scheduledNotificationPreferenceService, notificationConsentService);
        inOrder.verify(userRepository).saveAndFlush(any());
        inOrder.verify(subjectMappingService).createFor(42L);
        // 기본 설정 행은 방금 만든 subject로 같은 흐름에서 생성된다(재조회 없음).
        inOrder.verify(pushPreferenceService).createDefaultIfAbsent(SUBJECT_ID);
        inOrder.verify(scheduledNotificationPreferenceService)
                .createDefaultIfAbsent(SUBJECT_ID, ScheduledNotificationType.DAILY_REMINDER);
        inOrder.verify(notificationConsentService).createDefaultIfAbsent(SUBJECT_ID);
    }

    @Test
    void provision_mappingFailure_propagates() {
        when(userRepository.saveAndFlush(any())).thenReturn(savedUser(42L));
        IllegalStateException failure = new IllegalStateException("mapping insert failed");
        doThrow(failure).when(subjectMappingService).createFor(anyLong());

        assertThatThrownBy(() ->
                newUserProvisioner.provision(Provider.GOOGLE, "sub-123", "e@x.com", "nick"))
                .isSameAs(failure);
    }

    @Test
    void provision_defaultPreferenceFailure_propagates() {
        // 기본 설정 행 생성 실패도 가입 transaction 전체를 되돌린다 — 설정 없는 회원을 남기지 않는다.
        when(userRepository.saveAndFlush(any())).thenReturn(savedUser(42L));
        when(subjectMappingService.createFor(42L)).thenReturn(SUBJECT_ID);
        IllegalStateException failure = new IllegalStateException("preference insert failed");
        doThrow(failure).when(pushPreferenceService).createDefaultIfAbsent(SUBJECT_ID);

        assertThatThrownBy(() ->
                newUserProvisioner.provision(Provider.GOOGLE, "sub-123", "e@x.com", "nick"))
                .isSameAs(failure);
    }
}
