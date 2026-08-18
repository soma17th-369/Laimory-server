package com.laimory.server.push;

/**
 * 발송 대상 설치 하나 — FID와 그 소유 subject의 야간 광고 동의 상태를 함께 나른다.
 *
 * <p>sender가 각 FCM 호출 직전에 실제 KST 시각으로 야간 여부를 다시 판정하려면 target별 동의 상태가
 * 필요하다. subject ID는 sender로 넘기지 않는다 — worker가 batch 조회 결과를 이 boolean으로 투영해
 * 발송 계층이 소유자 식별자를 다루지 않게 한다(로그 유출 표면 축소).
 *
 * @param firebaseInstallationId 발송 target FID(opaque — 가공·로그 금지)
 * @param nightAdvertisingConsented 야간 광고성 수신 동의 여부. 정보성 발송에서는 사용하지 않는다.
 */
public record PushTarget(String firebaseInstallationId, boolean nightAdvertisingConsented) {

    /** 야간 판정이 필요 없는 정보성 발송용 target. */
    public static PushTarget informational(String firebaseInstallationId) {
        return new PushTarget(firebaseInstallationId, false);
    }
}
