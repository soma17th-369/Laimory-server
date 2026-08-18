package com.laimory.server.push.service;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.push.NotificationConsentSource;
import com.laimory.server.push.NotificationConsentType;
import com.laimory.server.push.OptOutTokens;
import com.laimory.server.push.entity.NotificationConsentEvent;
import com.laimory.server.push.entity.PushRegistration;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 로그인 없이 알림에서 바로 수행하는 광고 수신거부. 광고성 정보에 무료 수신거부 수단을 제공하기 위한
 * 경로이며 동의하거나 다른 설정을 바꾸는 권한은 주지 않는다.
 *
 * <p>UNIQUE FID로 현재 등록 한 행을 잠근 뒤 그 행의 owner subject와 token hash를 같은 잠금 아래에서
 * 읽는다 — 계정 전환과 경합해도 "지금 이 설치의 owner"에게만 철회가 적용되고, 이전 owner가 들고 있던
 * token으로 현재 owner의 동의를 철회할 수 없다. 같은 FID의 동시 재시도는 이 row lock으로 직렬화되므로
 * 뒤따르는 요청은 이미 기록된 증적을 그대로 돌려받는다.
 *
 * <p>등록은 삭제하지 않는다 — 정보성 알림 수신과 같은 요청의 재시도가 그대로 유지된다.
 *
 * <p>FID 부재·token 미제출·hash 불일치는 모두 같은 401로 수렴한다. 구분해서 알려주면 임의의 FID로
 * 등록 존재 여부를 캐낼 수 있기 때문이다. 로그에도 FID·token은 남기지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PushOptOutService {

    private final PushRegistrationService pushRegistrationService;
    private final NotificationConsentService notificationConsentService;

    /**
     * 설치가 제시한 credential을 검증하고 그 subject의 광고·야간 동의를 철회한다.
     *
     * @return 남긴 증적(재시도면 기존 증적) — 앱이 처리 결과로 표시한다
     */
    @Transactional
    public List<NotificationConsentEvent> optOut(String applicationVersion, UUID clientRequestId,
                                                  String firebaseInstallationId, String optOutToken) {
        // applicationVersion: 버전별 처리 분기 지점(현재 단일 버전이라 분기 없음).
        if (clientRequestId == null) {
            throw new IllegalArgumentException("clientRequestId is required");
        }
        PushRegistration registration = pushRegistrationService.findForOptOut(firebaseInstallationId)
                .orElseThrow(() -> invalidCredential());
        if (!OptOutTokens.matches(registration.getOptOutTokenHash(), optOutToken)) {
            throw invalidCredential();
        }
        // 일반 광고 동의 철회가 ON이던 야간 동의도 같은 transaction에서 내린다.
        return notificationConsentService.apply(registration.getSubjectId(), clientRequestId,
                NotificationConsentType.ADVERTISING_PUSH, false, null,
                NotificationConsentSource.INSTALLATION_OPT_OUT);
    }

    private static BusinessException invalidCredential() {
        // 어떤 검증에서 걸렸는지는 남기지 않는다 — FID 존재 여부가 로그로도 새지 않게 한다.
        log.warn("push opt-out rejected: invalid installation credential");
        return new BusinessException(ExceptionType.PUSH_OPT_OUT_TOKEN_INVALID);
    }
}
