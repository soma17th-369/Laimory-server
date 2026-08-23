package com.laimory.server.auth.controller;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * 로그인 핸드오프 링크({@code /auth/app?code=...})의 브라우저 폴백 안내 페이지.
 *
 * <p>정상 경로에서는 이 URL이 App Link로 앱에 배달돼 브라우저가 열리지 않는다. 폴백(assetlinks 미검증
 * 기기·데스크톱 브라우저)에서만 이 페이지가 뜬다. <b>code 쿼리는 읽지도, 표시하지도 않는다</b> —
 * app_code가 화면·로그에 노출되지 않게(서버 access 로그도 query 제외).
 *
 * <p>앱-facing API가 아니라 사람용 HTML이라 {@code ApiUrls} prefix 밖 단독 경로다(Swagger 비노출).
 */
@Hidden
@Controller
public class AuthHandoffPageController {

    private static final String PAGE = """
            <!doctype html>
            <html lang="ko">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <meta name="robots" content="noindex">
            <title>Laimory</title>
            </head>
            <body style="font-family: sans-serif; text-align: center; padding-top: 4rem;">
            <p>Laimory 앱에서 로그인을 완료해 주세요.</p>
            <p style="color: #888;">이 창은 닫아도 됩니다.</p>
            </body>
            </html>
            """;

    @GetMapping(value = "/auth/app", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String handoffLanding() {
        return PAGE;
    }
}
