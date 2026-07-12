package com.laimory.server.common.logging;

import java.util.Set;

/**
 * access 로그에서 제외하는 경로의 단일 관리 지점.
 *
 * <p>헬스체크·favicon처럼 신호 없는 트래픽이 로그를 채우지 않게 한다. 제외는 <b>정상 완료에만</b>
 * 적용된다 — 에러 코드가 심겼거나 미처리 예외가 전파된 요청은 경로와 무관하게 남긴다(제외 경로의
 * 장애가 로그에서 사라지면 안 되므로). tx 발급·MDC·응답 헤더는 제외와 무관하게 항상 유지된다.
 */
final class ExcludedPaths {

    /** 제외 대상 — 헬스체크(/status)와 브라우저 자동 요청(favicon: /kibana를 브라우저로 열면 실제 유입). */
    private static final Set<String> PATHS = Set.of("/status", "/favicon.ico");

    static boolean contains(String path) {
        return PATHS.contains(path);
    }

    private ExcludedPaths() {
    }
}
