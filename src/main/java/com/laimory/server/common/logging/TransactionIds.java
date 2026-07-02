package com.laimory.server.common.logging;

import com.laimory.server.common.id.UuidV7;
import java.util.UUID;
import org.slf4j.MDC;

/**
 * 요청 단위 추적 식별자(transactionId) 유틸.
 *
 * <p>값은 UUIDv7 원문 문자열이며, {@link TransactionIdFilter}가 요청 시작 시 MDC에 넣고
 * 응답 헤더 {@value #HEADER_NAME}로도 내려준다. envelope 응답의 {@code header.transactionId}는
 * {@link #current()}로 같은 MDC 값을 읽으므로 헤더와 항상 일치한다.
 */
public final class TransactionIds {

    /** 요청 헤더(클라이언트 제공 시 재사용) 겸 응답 헤더 이름. */
    public static final String HEADER_NAME = "X-Transaction-Id";

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

    /**
     * 클라이언트가 보낸 값을 재사용해도 되는지 검증한다. UUIDv7만 신뢰한다.
     *
     * <p>형식 검증이 곧 sanitization이다 — MDC를 거쳐 로그 라인에 찍히는 값이므로
     * 임의 문자열(log injection·과길이)을 여기서 차단한다.
     *
     * @return 파싱 가능한 UUID이고 version이 7이면 true
     */
    public static boolean isValidV7(String value) {
        if (value == null || value.length() != 36) {
            return false;
        }
        try {
            return UUID.fromString(value).version() == 7;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
