package com.laimory.server.terms;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 이 operation 진입 전에 해당 단계의 현재 필수 약관 동의를 추가로 요구한다 — 기본 {@code LOGIN} gate에
 * 더해지는 단계 표식이다(예: 사진 presign·draft 생성의 {@link TermStage#TIMELINE_FIRST_CREATE}).
 *
 * <p>{@code *Api} interface method에 선언한다. 검사는 S3 presign 발급·외부 호출·DB/Redis write보다 먼저
 * interceptor에서 끝난다.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiredTermsStage {

    TermStage value();
}
