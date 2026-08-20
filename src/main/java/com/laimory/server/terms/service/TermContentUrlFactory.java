package com.laimory.server.terms.service;

import com.laimory.server.terms.TermType;
import java.net.URI;
import java.net.URISyntaxException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 버전별 약관 원문 page URL 생성 — 공개 응답의 {@code contentUrl} 단일 소유자다.
 *
 * <p>원문은 Server DB가 아니라 {@code laimory.app}에 게시된 정적 HTML이 소유하고, URL은
 * {@code base + / + TermType.contentSlug + / + version}으로 결정된다 — 그래서 문서 행에
 * {@code content_url} 컬럼을 두지 않는다. Server는 요청·기동 중 이 URL을 HTTP로 조회하지 않는다
 * (응답 latency·가용성을 외부 page에 결합하지 않는다 — 게시 여부는 배포 게이트가 검증한다).
 *
 * <p>base URL은 기동 시 한 번 검증해 잘못된 설정이 첫 요청의 5xx로 미뤄지지 않게 한다. 게시 대상이
 * 단일 사이트라 host·path까지 고정 값으로 검사한다 — 오타 설정이 존재하지 않는 원문 page를 가리키는
 * 응답으로 새는 것보다 기동 실패가 낫다.
 */
@Component
public class TermContentUrlFactory {

    static final String BASE_URL_PROPERTY = "app.terms.public-base-url";
    private static final String EXPECTED_SCHEME = "https";
    private static final String EXPECTED_HOST = "laimory.app";
    private static final String EXPECTED_PATH = "/terms";

    private final URI baseUri;

    public TermContentUrlFactory(@Value("${" + BASE_URL_PROPERTY + "}") String baseUrl) {
        this.baseUri = validateBaseUrl(baseUrl);
    }

    /**
     * 종류·버전의 원문 page URL. {@code termType}·{@code version}은 NOT NULL 컬럼에서 온 내부 인자라
     * 여기서 새 입력 검증 분기를 두지 않는다. version은 opaque path segment로 인코딩한다 —
     * {@code /}·공백이 섞여도 segment 밖으로 빠져나가지 않는다.
     */
    public URI create(TermType termType, String version) {
        return UriComponentsBuilder.fromUri(baseUri)
                .pathSegment(termType.contentSlug(), version)
                .build()
                .encode()
                .toUri();
    }

    private static URI validateBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw invalid(baseUrl, "must not be blank");
        }
        URI uri;
        try {
            uri = new URI(baseUrl);
        } catch (URISyntaxException e) {
            throw invalid(baseUrl, "must be a valid URI");
        }
        if (!uri.isAbsolute()) {
            throw invalid(baseUrl, "must be an absolute URI");
        }
        if (!EXPECTED_SCHEME.equals(uri.getScheme())) {
            throw invalid(baseUrl, "scheme must be exactly " + EXPECTED_SCHEME);
        }
        if (!EXPECTED_HOST.equals(uri.getHost())) {
            throw invalid(baseUrl, "host must be exactly " + EXPECTED_HOST);
        }
        if (uri.getPort() != -1) {
            throw invalid(baseUrl, "must not declare a port");
        }
        if (uri.getUserInfo() != null) {
            throw invalid(baseUrl, "must not declare user info");
        }
        if (uri.getQuery() != null || uri.getFragment() != null) {
            throw invalid(baseUrl, "must not declare a query or fragment");
        }
        if (!EXPECTED_PATH.equals(uri.getPath())) {
            throw invalid(baseUrl, "path must be exactly " + EXPECTED_PATH + " (no trailing slash)");
        }
        return uri;
    }

    private static IllegalStateException invalid(String baseUrl, String requirement) {
        return new IllegalStateException(BASE_URL_PROPERTY + " is invalid: " + requirement
                + " (configured value: " + baseUrl + ")");
    }
}
