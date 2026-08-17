package com.laimory.server.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

import com.laimory.server.user.Provider;
import com.laimory.server.user.entity.User;
import com.laimory.server.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * provision 계약: users insert(flush)로 userId를 확보한 뒤 같은 흐름에서 subject mapping을 만든다.
 * mapping 실패는 그대로 전파된다 — 한 transaction rollback으로 user까지 사라지는 것은 @Transactional의
 * 몫이며 통합 테스트가 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class NewUserProvisionerTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private SubjectMappingService subjectMappingService;

    @InjectMocks
    private NewUserProvisioner newUserProvisioner;

    private static User savedUser(long userId) {
        User user = User.of(Provider.GOOGLE, "sub-123", "e@x.com", "nick");
        ReflectionTestUtils.setField(user, "userId", userId); // IDENTITY 채번 결과 재현
        return user;
    }

    @Test
    void provision_savesUserThenCreatesMappingWithGeneratedId() {
        User saved = savedUser(42L);
        when(userRepository.saveAndFlush(any())).thenReturn(saved);

        User result = newUserProvisioner.provision(Provider.GOOGLE, "sub-123", "e@x.com", "nick");

        assertThat(result).isSameAs(saved);
        InOrder inOrder = inOrder(userRepository, subjectMappingService);
        inOrder.verify(userRepository).saveAndFlush(any());
        inOrder.verify(subjectMappingService).createFor(42L);
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
}
