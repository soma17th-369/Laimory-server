package com.laimory.server.terms;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 필수 약관 gate의 명시적 exemption — 동의를 완료하거나 계정을 확인·정리하는 operation만
 * 붙인다(동의 등록/이력, 내 회원 조회, push 등록 PUT/DELETE, 후속 #305의 회원 탈퇴).
 *
 * <p>{@code *Api} interface method에 선언한다({@code HandlerMethod} annotation 탐색이 interface method를
 * 포함). raw path 문자열 allowlist를 만들지 않기 위한 계약이며, bearer 인증(401)은 그대로 요구된다 —
 * 이 annotation은 약관 gate(403)만 면제한다.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface LoginTermsExempt {
}
