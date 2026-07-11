package com.laimory.server.common.logging;

import com.laimory.server.common.id.UuidV7;

/**
 * 요청 단위 추적 식별자(transactionId) 유틸.
 *
 * <p>값은 UUIDv7 원문 문자열이며, {@link TransactionIdFilter}가 요청 시작 시 새로 발급해 MDC에 넣고
 * 같은 값을 응답 헤더 {@code Transaction-Id}로 노출한다. 클라이언트 노출 채널은 이 응답 헤더
 * 하나뿐이다(envelope body에는 담지 않는다).
 */
public final class TransactionIds {

    /** MDC 키. 로그 패턴/JSON encoder가 이 키로 자동 출력한다. */
    public static final String MDC_KEY = "transactionId";

    /** 클라이언트 노출용 HTTP 응답 헤더 이름. {@link TransactionIdFilter}가 요청마다 설정한다. */
    public static final String HEADER_NAME = "Transaction-Id";

    private TransactionIds() {
    }

    /** 새 transactionId(UUIDv7 문자열)를 생성한다. */
    public static String newId() {
        return UuidV7.randomUuidV7().toString();
    }
}
