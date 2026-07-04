package com.laimory.server.common.logging;

import com.laimory.server.common.id.UuidV7;
import org.slf4j.MDC;

/**
 * 요청 단위 추적 식별자(transactionId) 유틸.
 *
 * <p>값은 UUIDv7 원문 문자열이며, {@link TransactionIdFilter}가 요청 시작 시 새로 발급해 MDC에 넣는다.
 * 클라이언트 노출 채널은 envelope 응답의 {@code header.transactionId} 하나뿐이고({@link #current()}로
 * 같은 MDC 값을 읽는다), HTTP 헤더 채널은 두지 않는다 — 소비자가 생기면 그때 추가한다.
 */
public final class TransactionIds {

    /** MDC 키. 로그 패턴/JSON encoder가 이 키로 자동 출력한다. */
    public static final String MDC_KEY = "transactionId";

    private TransactionIds() {
    }

    /** 새 transactionId(UUIDv7 문자열)를 생성한다. */
    public static String newId() {
        return UuidV7.randomUuidV7().toString();
    }

    /** 현재 요청의 transactionId. HTTP 요청 밖(스케줄러 등)이면 null. */
    public static String current() {
        return MDC.get(MDC_KEY);
    }
}
