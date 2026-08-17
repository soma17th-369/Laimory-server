package com.laimory.server.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.user.Provider;
import com.laimory.server.user.entity.User;
import com.laimory.server.user.repository.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * findOrCreate 계약: 기존 조회 우선, 미존재면 NewUserProvisioner로 생성(user+subject mapping 한
 * transaction), 동시 최초 로그인(UNIQUE 위반)은 재조회로 수렴. Kakao 기존 사용자는 non-null 닉네임만
 * ACTIVE 조건부 nickname-only UPDATE로 갱신하고(#305 — entity 저장으로 탈퇴 status/identity를 되살리지
 * 않음, 영향 0행이면 갱신 폐기) 누락 claim은 기존 값을 보존한다. getProfile 계약: 인증 userId 조회,
 * 행 없음은 기존 401 {@code -2001}로 수렴(존재 비노출). 인프라 0.
 */
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private NewUserProvisioner newUserProvisioner;

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
        verify(newUserProvisioner, never()).provision(any(), any(), any(), any());
    }

    @Test
    void findOrCreate_absentUser_provisionsAndReturnsCreated() {
        User created = User.of(PROVIDER, PROVIDER_USER_ID, "e@x.com", "nick");
        when(userRepository.findByProviderAndProviderUserId(PROVIDER, PROVIDER_USER_ID))
                .thenReturn(Optional.empty());
        when(newUserProvisioner.provision(PROVIDER, PROVIDER_USER_ID, "e@x.com", "nick"))
                .thenReturn(created);

        User result = userService.findOrCreate(PROVIDER, PROVIDER_USER_ID, "e@x.com", "nick");

        assertThat(result).isSameAs(created);
        verify(newUserProvisioner).provision(PROVIDER, PROVIDER_USER_ID, "e@x.com", "nick");
        // 생성은 provisioner 경유만 — UserService가 직접 insert하지 않는다.
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void findOrCreate_concurrentInsertLoser_convergesToRefetchedRow() {
        User winnerRow = User.of(PROVIDER, PROVIDER_USER_ID, "e@x.com", "nick");
        when(userRepository.findByProviderAndProviderUserId(PROVIDER, PROVIDER_USER_ID))
                .thenReturn(Optional.empty())   // 최초 조회: 없음
                .thenReturn(Optional.of(winnerRow)); // provision 패배 후 재조회: 상대가 만든 행
        when(newUserProvisioner.provision(any(), any(), any(), any()))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        User result = userService.findOrCreate(PROVIDER, PROVIDER_USER_ID, "e@x.com", "nick");

        assertThat(result).isSameAs(winnerRow);
    }

    @Test
    void findOrCreate_existingKakaoUser_refreshesNicknameViaConditionalUpdateOnly() {
        User existing = User.of(Provider.KAKAO, PROVIDER_USER_ID, null, "옛닉");
        when(userRepository.findByProviderAndProviderUserId(Provider.KAKAO, PROVIDER_USER_ID))
                .thenReturn(Optional.of(existing));
        when(userRepository.updateNicknameIfActive(Provider.KAKAO, PROVIDER_USER_ID, "새닉")).thenReturn(1);

        User result = userService.findOrCreate(Provider.KAKAO, PROVIDER_USER_ID, null, "새닉");

        assertThat(result.getNickname()).isEqualTo("새닉");
        // entity 전체 저장 금지(#305) — 탈퇴와 겹친 stale 저장이 status/provider identity를 되살린다.
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void findOrCreate_kakaoNicknameUpdateLostToWithdrawal_keepsExistingNicknameWithoutResurrection() {
        // 조회 후 탈퇴가 commit되면 ACTIVE 조건부 UPDATE가 0행이다 — 갱신을 버리고 어떤 저장도 하지 않는다.
        User existing = User.of(Provider.KAKAO, PROVIDER_USER_ID, null, "옛닉");
        when(userRepository.findByProviderAndProviderUserId(Provider.KAKAO, PROVIDER_USER_ID))
                .thenReturn(Optional.of(existing));
        when(userRepository.updateNicknameIfActive(Provider.KAKAO, PROVIDER_USER_ID, "새닉")).thenReturn(0);

        User result = userService.findOrCreate(Provider.KAKAO, PROVIDER_USER_ID, null, "새닉");

        assertThat(result).isSameAs(existing);
        assertThat(result.getNickname()).isEqualTo("옛닉");
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void findOrCreate_existingKakaoUser_missingNickname_keepsExistingWithoutSaving() {
        User existing = User.of(Provider.KAKAO, PROVIDER_USER_ID, null, "옛닉");
        when(userRepository.findByProviderAndProviderUserId(Provider.KAKAO, PROVIDER_USER_ID))
                .thenReturn(Optional.of(existing));

        User result = userService.findOrCreate(Provider.KAKAO, PROVIDER_USER_ID, null, null);

        assertThat(result).isSameAs(existing);
        assertThat(result.getNickname()).isEqualTo("옛닉"); // 누락 claim은 철회/응답 누락 구분 불가 — 보존
        verify(userRepository, never()).saveAndFlush(any());
        verify(userRepository, never()).updateNicknameIfActive(any(), any(), any());
    }

    @Test
    void findOrCreate_existingGoogleUser_neverUpdatesProfile() {
        User existing = User.of(Provider.GOOGLE, PROVIDER_USER_ID, "e@x.com", "이전 이름");
        when(userRepository.findByProviderAndProviderUserId(Provider.GOOGLE, PROVIDER_USER_ID))
                .thenReturn(Optional.of(existing));

        User result = userService.findOrCreate(Provider.GOOGLE, PROVIDER_USER_ID, "e@x.com", "바뀐 이름");

        assertThat(result).isSameAs(existing);
        assertThat(result.getNickname()).isEqualTo("이전 이름"); // Google 갱신은 이 이슈 범위 밖 — 기존 동작 유지
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void findOrCreate_kakaoConcurrentInsertLoser_appliesNicknameToWinner() {
        User winnerRow = User.of(Provider.KAKAO, PROVIDER_USER_ID, null, "승자닉");
        when(userRepository.findByProviderAndProviderUserId(Provider.KAKAO, PROVIDER_USER_ID))
                .thenReturn(Optional.empty())   // 최초 조회: 없음
                .thenReturn(Optional.of(winnerRow)); // provision 패배 후 재조회: 상대가 만든 행
        when(newUserProvisioner.provision(any(), any(), any(), any()))
                .thenThrow(new DataIntegrityViolationException("duplicate key")); // 내 insert 패배
        when(userRepository.updateNicknameIfActive(Provider.KAKAO, PROVIDER_USER_ID, "이번닉"))
                .thenReturn(1); // 승자 행 닉네임 조건부 갱신

        User result = userService.findOrCreate(Provider.KAKAO, PROVIDER_USER_ID, null, "이번닉");

        assertThat(result).isSameAs(winnerRow);
        assertThat(result.getNickname()).isEqualTo("이번닉");
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void getProfile_existingUser_returnsRow() {
        User existing = User.of(PROVIDER, PROVIDER_USER_ID, "e@x.com", "nick");
        when(userRepository.findById(7L)).thenReturn(Optional.of(existing));

        User result = userService.getProfile("v1", 7L);

        assertThat(result).isSameAs(existing);
    }

    @Test
    void getProfile_missingUserRow_throwsAuthenticationRequiredWithoutUserId() {
        when(userRepository.findById(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.getProfile("v1", 7L))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    // 기존 401 -2001 계약으로 수렴 — 탈퇴 여부·내부 식별자 존재를 노출하지 않는다.
                    assertThat(e.getExceptionType()).isEqualTo(ExceptionType.API_AUTHENTICATION_REQUIRED);
                    assertThat(e.getArgs()).isEmpty();
                    assertThat(e.getMessage()).doesNotContain("7");
                });
    }

    @Test
    void findOrCreate_provisionFailsAndRefetchEmpty_propagatesOriginalException() {
        DataIntegrityViolationException original = new DataIntegrityViolationException("duplicate key");
        when(userRepository.findByProviderAndProviderUserId(PROVIDER, PROVIDER_USER_ID))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.empty());
        when(newUserProvisioner.provision(any(), any(), any(), any())).thenThrow(original);

        assertThatThrownBy(() -> userService.findOrCreate(PROVIDER, PROVIDER_USER_ID, "e@x.com", "nick"))
                .isSameAs(original);
    }
}
