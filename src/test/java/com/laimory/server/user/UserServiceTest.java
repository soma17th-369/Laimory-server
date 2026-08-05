package com.laimory.server.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * findOrCreate 계약: 기존 조회 우선, 미존재면 생성, 동시 최초 로그인(UNIQUE 위반)은 재조회로 수렴.
 * Kakao 기존 사용자는 non-null 닉네임만 갱신하고 누락 claim은 기존 값을 보존한다. 인프라 0.
 *
 * <p>User Memory 계약: 조회는 사용자 없음·메모리 없음을 구분하지 않고 빈 Optional이며, 교체는 병합이
 * 아닌 전체 대체다(null은 제거). 실제 JSON 왕복은 persistence 통합 테스트가 소유한다.
 */
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
    void findOrCreate_existingKakaoUser_refreshesNicknameAndSaves() {
        User existing = User.of(Provider.KAKAO, PROVIDER_USER_ID, null, "옛닉");
        when(userRepository.findByProviderAndProviderUserId(Provider.KAKAO, PROVIDER_USER_ID))
                .thenReturn(Optional.of(existing));
        when(userRepository.saveAndFlush(existing)).thenReturn(existing);

        User result = userService.findOrCreate(Provider.KAKAO, PROVIDER_USER_ID, null, "새닉");

        assertThat(result.getNickname()).isEqualTo("새닉");
        verify(userRepository).saveAndFlush(existing);
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
                .thenReturn(Optional.of(winnerRow)); // insert 패배 후 재조회: 상대가 만든 행
        when(userRepository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("duplicate key")) // 내 insert 패배
                .thenAnswer(invocation -> invocation.getArgument(0));            // 승자 닉네임 갱신 저장

        User result = userService.findOrCreate(Provider.KAKAO, PROVIDER_USER_ID, null, "이번닉");

        assertThat(result).isSameAs(winnerRow);
        assertThat(result.getNickname()).isEqualTo("이번닉");
    }

    @Test
    void findUserMemory_absentUserOrAbsentMemory_isEmpty() {
        when(userRepository.findById(1L)).thenReturn(Optional.empty());
        when(userRepository.findById(2L))
                .thenReturn(Optional.of(User.of(PROVIDER, PROVIDER_USER_ID, "e@x.com", "nick")));

        assertThat(userService.findUserMemory(1L)).isEmpty();
        assertThat(userService.findUserMemory(2L)).isEmpty();
    }

    @Test
    void replaceUserMemory_replacesWholeDocument() {
        User user = User.of(PROVIDER, PROVIDER_USER_ID, "e@x.com", "nick");
        user.replaceUserMemory(new ObjectMapper().createObjectNode().put("before", 1));
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        JsonNode next = new ObjectMapper().createObjectNode().put("after", 2);

        userService.replaceUserMemory(7L, next);

        // 병합이 아니라 교체다 — 이전 문서의 key는 남지 않는다.
        assertThat(user.getUserMemory()).isSameAs(next);
        verify(userRepository).saveAndFlush(user);
    }

    @Test
    void replaceUserMemory_null_clearsMemory() {
        User user = User.of(PROVIDER, PROVIDER_USER_ID, "e@x.com", "nick");
        user.replaceUserMemory(new ObjectMapper().createObjectNode().put("before", 1));
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));

        userService.replaceUserMemory(7L, null);

        assertThat(user.getUserMemory()).isNull();
    }

    @Test
    void replaceUserMemory_absentUser_rejects() {
        when(userRepository.findById(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.replaceUserMemory(9L, null))
                .isInstanceOf(IllegalArgumentException.class);
        verify(userRepository, never()).saveAndFlush(any());
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
