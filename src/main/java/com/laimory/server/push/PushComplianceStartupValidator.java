package com.laimory.server.push;

import org.springframework.stereotype.Component;

/**
 * 등록된 모든 알림 종류가 확정된 법적 분류를 갖는지 기동 시점에 검사한다.
 *
 * <p>분류 mapping 누락을 "안전한 기본값 = 정보성"으로 흡수하면 광고성 알림이 동의·야간 제한 없이
 * 나갈 수 있다. worker enable 여부와 무관하게 기동을 실패시켜, 설정 API만 배포된 상태에서도
 * {@code GET /a/api/{v}/push-settings}가 항상 non-null 분류를 반환하도록 보장한다.
 */
@Component
public class PushComplianceStartupValidator {

    public PushComplianceStartupValidator() {
        for (PushMessageType type : PushMessageType.values()) {
            if (type.complianceClass() == null) {
                throw new IllegalStateException(
                        "push message type requires a confirmed compliance class: " + type.name());
            }
        }
        for (ScheduledNotificationType type : ScheduledNotificationType.values()) {
            if (type.pushMessageType() == null || type.complianceClass() == null) {
                throw new IllegalStateException(
                        "scheduled notification type requires a confirmed compliance class: " + type.name());
            }
        }
    }
}
