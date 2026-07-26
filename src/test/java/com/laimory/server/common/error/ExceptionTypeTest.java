package com.laimory.server.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.event.Level;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

/** ExceptionType numeric code/status/level 카탈로그와 프레임워크 status 폴백 매핑을 고정한다. 인프라 0. */
class ExceptionTypeTest {

    @Test
    void nToOne_distinctInternalTypesShareOneClientCode_withDifferentLevels() {
        // 클라이언트 계약은 하나(재로그인) — 내부 구분(일상 vs 공격 신호)은 로그 레벨로만 갈린다. N:1의 존재 이유.
        assertThat(ExceptionType.APP_CODE_INVALID.code()).isEqualTo(-2002);
        assertThat(ExceptionType.APP_CODE_VERIFIER_MISMATCH.code()).isEqualTo(-2002);
        assertThat(ExceptionType.APP_CODE_INVALID.logLevel()).isEqualTo(Level.INFO);
        assertThat(ExceptionType.APP_CODE_VERIFIER_MISMATCH.logLevel()).isEqualTo(Level.WARN);

        assertThat(ExceptionType.REFRESH_TOKEN_INVALID.code()).isEqualTo(-2003);
        assertThat(ExceptionType.REFRESH_TOKEN_REUSED.code()).isEqualTo(-2003);
        assertThat(ExceptionType.REFRESH_TOKEN_INVALID.logLevel()).isEqualTo(Level.INFO);
        assertThat(ExceptionType.REFRESH_TOKEN_REUSED.logLevel()).isEqualTo(Level.WARN);
    }

    @Test
    void everyFailureCodeIsNegative_andSpecialOwnersHaveExactContract() {
        assertThat(ExceptionType.values()).allMatch(type -> type.code() < 0);
        assertThat(ExceptionType.AI_REPORTED_FAILURE)
                .extracting(ExceptionType::code, ExceptionType::status, ExceptionType::logLevel)
                .containsExactly(-1008, HttpStatus.BAD_GATEWAY, Level.WARN);
        assertThat(ExceptionType.AI_DISPATCH_FAILED)
                .extracting(ExceptionType::code, ExceptionType::status, ExceptionType::logLevel)
                .containsExactly(-1009, HttpStatus.BAD_GATEWAY, Level.ERROR);
        assertThat(ExceptionType.DRAFT_TASK_FAILURE_FALLBACK)
                .extracting(ExceptionType::code, ExceptionType::status, ExceptionType::logLevel)
                .containsExactly(-1011, HttpStatus.INTERNAL_SERVER_ERROR, Level.ERROR);
        assertThat(ExceptionType.OAUTH_LOGIN_FAILED)
                .extracting(ExceptionType::code, ExceptionType::status, ExceptionType::logLevel)
                .containsExactly(-2004, HttpStatus.UNAUTHORIZED, Level.WARN);
    }

    @Test
    void messageKeyIsDerivedFromNumericCode() {
        assertThat(ExceptionType.VALIDATION_FAILED.messageKey()).isEqualTo("ERROR_0400");
        assertThat(ExceptionType.AI_REPORTED_FAILURE.messageKey()).isEqualTo("ERROR_1008");
    }

    /**
     * 프레임워크 폴백 매핑 고정 — Spring 6.2의 {@code ResponseEntityExceptionHandler}는
     * 406(MediaTypeNotAcceptable)·503(AsyncRequestTimeout)·500(ConversionNotSupported 등)도 처리하므로
     * 미열거 status의 귀속을 계약으로 못 박는다. 응답 HTTP status는 framework 값이 보존되고
     * 여기서 정해지는 건 envelope 코드와 로그 타입뿐이다.
     */
    @ParameterizedTest
    @CsvSource({
            "400, MVC_REQUEST_REJECTED",
            "404, RESOURCE_NOT_FOUND",
            "405, METHOD_NOT_ALLOWED",
            "406, MVC_REQUEST_REJECTED",
            "409, MVC_REQUEST_REJECTED",
            "415, UNSUPPORTED_MEDIA_TYPE",
            "500, UNEXPECTED_ERROR",
            "502, UNEXPECTED_ERROR",
            "503, UNEXPECTED_ERROR"
    })
    void fromStatus_mapsFrameworkStatusToFallbackType(int status, ExceptionType expected) {
        assertThat(ExceptionType.fromStatus(HttpStatusCode.valueOf(status))).isEqualTo(expected);
    }
}
