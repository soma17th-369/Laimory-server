package com.laimory.server.common.logging;

import java.util.Set;

/**
 * access 로그에서 제외하는 경로의 단일 관리 지점.
 *
 * <p>등재 기준: <b>정상 완료가 아무 정보도 담지 않는 트래픽</b>(에러는 항상 남는다) —
 * 성공이 뉴스가 아닌 요청만 넣는다. 헬스체크 200은 기대되는 평상 상태라 정보량이 0이지만,
 * 같은 경로의 에러·미처리 예외는 정보이므로 경로와 무관하게 남긴다(제외 경로의 장애가
 * 로그에서 사라지면 안 된다). 실사용 API는 정상 완료도 정보(latency·트래픽 패턴)라 제외 금지.
 * tx 발급·MDC·응답 헤더는 제외와 무관하게 항상 유지된다.
 */
final class ExcludedPaths {

    /** 제외 대상 — 한 줄에 하나씩 나열한다(정확 일치만, 패턴 불가). */
    private static final Set<String> PATHS = Set.of(
            "/status",       // 순수 헬스체크 프로브
            "/favicon.ico"   // 브라우저 자동 요청(/kibana를 브라우저로 열면 실제 유입)
    );

    static boolean contains(String path) {
        return PATHS.contains(path);
    }

    private ExcludedPaths() {
    }
}
