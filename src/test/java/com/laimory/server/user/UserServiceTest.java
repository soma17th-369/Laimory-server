package com.laimory.server.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

/** findOrCreate 계약: 기존 조회 우선, 미존재면 생성, 동시 최초 로그인(UNIQUE 위반)은 재조회로 수렴. 인프라 0. */
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private UserService userService;

    private static final Provider PROVIDER = Provider.GOOGLE;
    private static final String PROVIDER_USER_ID = "sub-123";

    @Test
    void findOrCreate_existingUser_returnsItWithoutSaving() {
        User existing = User.of(PROVIDER, PROVIDER_USER_ID, "e@x.com", "nick");
        when(userRepository.findByProviderAndProviderUserId(PROVIDER, PROVIDER_USER_ID))
                .thenReturn(Optional.of(existing));

        User result = userService.findOrCreate(PROVIDER, PROVIDER_USER_ID, "e@x.com", "nick");

        assertThat(result).isSameAs(existing);
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void findOrCreate_absentUser_savesAndReturnsCreated() {
        User created = User.of(PROVIDER, PROVIDER_USER_ID, "e@x.com", "nick");
        when(userRepository.findByProviderAndProviderUserId(PROVIDER, PROVIDER_USER_ID))
                .thenReturn(Optional.empty());
        when(userRepository.saveAndFlush(any())).thenReturn(created);

        User result = userService.findOrCreate(PROVIDER, PROVIDER_USER_ID, "e@x.com", "nick");

        assertThat(result).isSameAs(created);
        verify(userRepository).saveAndFlush(any());
    }

    @Test
    void findOrCreate_concurrentInsertLoser_convergesToRefetchedRow() {
        User winnerRow = User.of(PROVIDER, PROVIDER_USER_ID, "e@x.com", "nick");
        when(userRepository.findByProviderAndProviderUserId(PROVIDER, PROVIDER_USER_ID))
                .thenReturn(Optional.empty())   // 최초 조회: 없음
                .thenReturn(Optional.of(winnerRow)); // insert 패배 후 재조회: 상대가 만든 행
        when(userRepository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        User result = userService.findOrCreate(PROVIDER, PROVIDER_USER_ID, "e@x.com", "nick");

        assertThat(result).isSameAs(winnerRow);
    }

    @Test
    void findOrCreate_saveFailsAndRefetchEmpty_propagatesOriginalException() {
        DataIntegrityViolationException original = new DataIntegrityViolationException("duplicate key");
        when(userRepository.findByProviderAndProviderUserId(PROVIDER, PROVIDER_USER_ID))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.empty());
        when(userRepository.saveAndFlush(any())).thenThrow(original);

        assertThatThrownBy(() -> userService.findOrCreate(PROVIDER, PROVIDER_USER_ID, "e@x.com", "nick"))
                .isSameAs(original);
    }
}
