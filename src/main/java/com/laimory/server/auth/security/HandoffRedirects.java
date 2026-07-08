package com.laimory.server.auth.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * 로그인 결과를 앱에 전달하는 핸드오프 링크({@code https://{요청 host}/auth/app?...}) 조립.
 *
 * <p>커스텀 스킴({@code laimory://})이 아니라 claimed HTTPS App Link를 쓴다 — 커스텀 스킴은 타 앱도
 * 등록 가능해 탈취될 수 있고, App Link는 assetlinks.json으로 서명 검증된 앱에만 OS가 배달한다.
 * base는 현재 요청의 scheme/host에서 구성한다(forward-headers 전략으로 프록시 뒤에서도 https 도메인).
 */
final class HandoffRedirects {

    static final String HANDOFF_PATH = "/auth/app";

    private HandoffRedirects() {
    }

    static String uri(HttpServletRequest request, String param, String value) {
        return ServletUriComponentsBuilder.fromRequestUri(request)
                .replacePath(HANDOFF_PATH)
                .replaceQuery(null)
                .queryParam(param, value)
                .build()
                .toUriString();
    }
}
