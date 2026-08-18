package com.laimory.server.push;

import java.util.List;

/**
 * 푸시 발송 port(Firebase SDK 독립). 구현은 {@code app.push.mode}로 정확히 하나만 선택된다 —
 * noop(기본)·firebase. 매칭 구현이 없으면(오타) 주입 실패로 컨텍스트가 기동하지 않는다.
 *
 * <p>호출자는 문구를 만들지 않는다. {@link PushMessage#type()}이 고정 title/body와 법적 분류를 소유하고
 * 광고 표기·수신거부 안내·야간 제한은 구현이 공통으로 적용한다 — 개별 notifier가 빠뜨릴 수 없는 구조다.
 */
public interface PushMessageSender {

    /**
     * 주어진 설치들에 알림 한 건을 발송한다. 개별 실패는 결과 개수·무효 FID 목록으로 돌려주고 호출
     * 수준 전이 오류도 실패 개수로 흡수한다 — 발송 실패가 호출자 흐름으로 전파되지 않는 best-effort 계약이다.
     *
     * <p>광고성 메시지는 각 호출 직전의 실제 KST 시각이 야간이면 야간 동의가 없는 target을 제외한다
     * (결과의 {@code skippedCount}로 집계).
     */
    PushSendResult send(PushMessage message, List<PushTarget> targets);
}
