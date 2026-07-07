package com.laimory.server.auth.service;

import com.laimory.server.auth.repository.AppCodeStore;
import com.laimory.server.auth.token.AuthTokens;
import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ErrorCode;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * app_code(로그인 성공 → 앱 핸드오프용 일회용 코드) leaf 서비스. 자신과 1:1인 {@link AppCodeStore}에만 접근한다.
 *
 * <p>app_code는 딥링크 URL로 노출되는 값이라 자체 권한이 없고, 60초 안에 한 번만 토큰 쌍으로 교환된다.
 * 교환은 발급 시 바인딩된 challenge와 앱이 제시한 verifier의 대조(핸드오프 PKCE)까지 통과해야 한다 —
 * 코드가 탈취돼도 verifier 없인 교환 불가.
 */
@Service
public class AppCodeService {

    private static final Logger log = LoggerFactory.getLogger(AppCodeService.class);

    private final AppCodeStore appCodeStore;
    private final Duration appCodeTtl;

    public AppCodeService(AppCodeStore appCodeStore,
                          @Value("${app.auth.app-code-ttl}") Duration appCodeTtl) {
        this.appCodeStore = appCodeStore;
        this.appCodeTtl = appCodeTtl;
    }

    /** 일회용 app_code를 발급한다. Redis엔 해시 키로 {userId, challenge}만 저장하고 원문을 반환한다. */
    public String issue(long userId, String appChallenge) {
        if (appChallenge == null || appChallenge.isBlank()) {
            throw new IllegalArgumentException("appChallenge must not be blank");
        }
        String raw = AuthTokens.generate();
        appCodeStore.save(AuthTokens.sha256Hex(raw), new AppCodeStore.AppCodeEntry(userId, appChallenge), appCodeTtl);
        return raw;
    }

    /**
     * app_code를 원자 소비(GETDEL)하고 verifier를 검증해 userId를 반환한다.
     * 무효/만료/이미 소비/verifier 불일치는 전부 {@code ERROR_2002} — 사유 구분은 공격자에게만 유용하다.
     */
    public long consume(String appCode, String appVerifier) {
        if (appCode == null || appCode.isBlank() || appVerifier == null || appVerifier.isBlank()) {
            throw new IllegalArgumentException("appCode/appVerifier must not be blank");
        }
        AppCodeStore.AppCodeEntry entry = appCodeStore.consume(AuthTokens.sha256Hex(appCode));
        if (entry == null) {
            throw new BusinessException(ErrorCode.ERROR_2002);
        }
        if (!AuthTokens.matchesChallenge(appVerifier, entry.appChallenge())) {
            // 코드는 유효했는데 verifier가 틀림 = 딥링크 탈취 시도 가능성. 코드는 이미 소비돼 재시도 불가.
            log.warn("app_code verifier mismatch: userId={}", entry.userId());
            throw new BusinessException(ErrorCode.ERROR_2002);
        }
        return entry.userId();
    }
}
