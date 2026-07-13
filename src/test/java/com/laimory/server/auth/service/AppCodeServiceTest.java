package com.laimory.server.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.laimory.server.auth.repository.AppCodeStore;
import com.laimory.server.auth.token.AuthTokens;
import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ErrorCode;
import com.laimory.server.common.error.ExceptionType;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** app_code 발급·소비 계약: 해시 키 저장, GETDEL 소비 후 verifier(PKCE) 검증, 실패는 전부 ERROR_2002. 인프라 0. */
@ExtendWith(MockitoExtension.class)
class AppCodeServiceTest {

    @Mock
    private AppCodeStore appCodeStore;

    private static final Duration TTL = Duration.ofSeconds(60);
    private static final long USER_ID = 7L;

    private AppCodeService newService() {
        return new AppCodeService(appCodeStore, TTL);
    }

    @Test
    void issue_savesHashedKeyWithEntryAndTtl_returnsRawBase64UrlCode() {
        String verifier = AuthTokens.generate();
        String challenge = AuthTokens.challenge(verifier);

        String raw = newService().issue(USER_ID, challenge);

        assertThat(raw).matches("[A-Za-z0-9_-]+").hasSize(43);
        ArgumentCaptor<String> hashCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<AppCodeStore.AppCodeEntry> entryCaptor =
                ArgumentCaptor.forClass(AppCodeStore.AppCodeEntry.class);
        org.mockito.Mockito.verify(appCodeStore).save(hashCaptor.capture(), entryCaptor.capture(), eq(TTL));
        assertThat(hashCaptor.getValue()).isEqualTo(AuthTokens.sha256Hex(raw));
        assertThat(entryCaptor.getValue().userId()).isEqualTo(USER_ID);
        assertThat(entryCaptor.getValue().appChallenge()).isEqualTo(challenge);
    }

    @Test
    void consume_validVerifier_returnsUserId() {
        String verifier = AuthTokens.generate();
        String challenge = AuthTokens.challenge(verifier);
        String code = "app-code-raw";
        when(appCodeStore.consume(AuthTokens.sha256Hex(code)))
                .thenReturn(new AppCodeStore.AppCodeEntry(USER_ID, challenge));

        assertThat(newService().consume(code, verifier)).isEqualTo(USER_ID);
    }

    @Test
    void consume_missingEntry_throwsError2002() {
        when(appCodeStore.consume(AuthTokens.sha256Hex("app-code-raw"))).thenReturn(null);

        assertThatThrownBy(() -> newService().consume("app-code-raw", AuthTokens.generate()))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    // N:1 계약: 내부 타입이 뒤바뀌어도 code 단언만으론 통과하므로 타입까지 고정한다
                    assertThat(ex.getExceptionType()).isEqualTo(ExceptionType.APP_CODE_INVALID);
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.ERROR_2002);
                });
    }

    @Test
    void consume_verifierMismatch_throwsError2002() {
        String challenge = AuthTokens.challenge(AuthTokens.generate()); // 어떤 verifier의 challenge
        String wrongVerifier = AuthTokens.generate();                   // 다른 verifier
        String code = "app-code-raw";
        when(appCodeStore.consume(AuthTokens.sha256Hex(code)))
                .thenReturn(new AppCodeStore.AppCodeEntry(USER_ID, challenge));

        assertThatThrownBy(() -> newService().consume(code, wrongVerifier))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    // 탈취 시도 시그널(WARN 대상)이 일상 실패(INFO)로 강등되는 회귀를 잡는다
                    assertThat(ex.getExceptionType()).isEqualTo(ExceptionType.APP_CODE_VERIFIER_MISMATCH);
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.ERROR_2002);
                });
    }

    @Test
    void consume_blankArguments_throwIllegalArgument() {
        AppCodeService service = newService();

        assertThatThrownBy(() -> service.consume(null, "v")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.consume(" ", "v")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.consume("c", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.consume("c", " ")).isInstanceOf(IllegalArgumentException.class);
    }

}
