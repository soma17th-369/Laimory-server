package com.laimory.server.common.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class TrustedEdgeRequestFilterTest {

    /** 현행 dev와 같은 배선 — ALB 대역이 비어 있어 loopback nginx 엣지만 신뢰한다. */
    private final TrustedEdgeRequestFilter loopbackEdge = new TrustedEdgeRequestFilter(List.of());

    /** ALB 전환 후 배선 — ALB ENI가 사는 퍼블릭 서브넷 두 개를 신뢰한다. */
    private final TrustedEdgeRequestFilter proxyEdge =
            new TrustedEdgeRequestFilter(List.of("10.0.0.0/20", "10.0.16.0/20"));

    private static final String PROXY_PEER = "10.0.16.20";

    @Test
    void trustedLoopback_usesSingleNormalizedIpv4_andIgnoresXffAndUserAgent() throws Exception {
        MockHttpServletRequest request = trustedRequest();
        request.addHeader(TrustedEdgeRequestFilter.CLIENT_IP_HEADER, " 203.0.113.7\t");
        request.addHeader(TrustedEdgeRequestFilter.FORWARDED_FOR_HEADER, "198.51.100.9");
        request.addHeader("User-Agent", "203.0.113.99");

        HttpServletRequest resolved = run(loopbackEdge, request);

        assertThat(resolved.getRemoteAddr()).isEqualTo("203.0.113.7");
    }

    @Test
    void trustedLoopback_normalizesCompressedIpv6() throws Exception {
        MockHttpServletRequest request = trustedRequest();
        request.addHeader(TrustedEdgeRequestFilter.CLIENT_IP_HEADER, "2001:0DB8::1");

        assertThat(run(loopbackEdge, request).getRemoteAddr()).isEqualTo("2001:db8::1");
    }

    @Test
    void trustedLoopback_acceptsIpv4EmbeddedIpv6() throws Exception {
        MockHttpServletRequest request = trustedRequest();
        request.addHeader(TrustedEdgeRequestFilter.CLIENT_IP_HEADER, "::ffff:192.0.2.128");

        assertThat(run(loopbackEdge, request).getRemoteAddr()).isEqualTo("::ffff:c000:280");
    }

    @ParameterizedTest
    @MethodSource("canonicalIpv6")
    void trustedLoopback_canonicalizesValidIpv6GroupBoundaries(String header, String expected) throws Exception {
        MockHttpServletRequest request = trustedRequest();
        request.addHeader(TrustedEdgeRequestFilter.CLIENT_IP_HEADER, header);

        assertThat(run(loopbackEdge, request).getRemoteAddr()).isEqualTo(expected);
    }

    @ParameterizedTest
    @MethodSource("invalidIpHeaders")
    void malformedOrMissingClientIp_fallsBackToSocketPeer(List<String> values) throws Exception {
        MockHttpServletRequest request = trustedRequest();
        values.forEach(value -> request.addHeader(TrustedEdgeRequestFilter.CLIENT_IP_HEADER, value));

        assertThat(run(loopbackEdge, request).getRemoteAddr())
                .isEqualTo(TrustedEdgeRequestFilter.TRUSTED_SOCKET_PEER);
    }

    @Test
    void repeatedIdenticalClientIp_stillFallsBackToSocketPeer() throws Exception {
        MockHttpServletRequest request = trustedRequest();
        request.addHeader(TrustedEdgeRequestFilter.CLIENT_IP_HEADER, "203.0.113.7");
        request.addHeader(TrustedEdgeRequestFilter.CLIENT_IP_HEADER, "203.0.113.7");

        assertThat(run(loopbackEdge, request).getRemoteAddr())
                .isEqualTo(TrustedEdgeRequestFilter.TRUSTED_SOCKET_PEER);
    }

    /** 전환기 계약: ALB 대역을 설정해도 nginx 경유(loopback) 요청은 기존 계약 그대로 동작한다. */
    @Test
    void loopbackStillTrusted_whileProxyCidrsAreConfigured() throws Exception {
        MockHttpServletRequest request = trustedRequest();
        request.addHeader(TrustedEdgeRequestFilter.CLIENT_IP_HEADER, "203.0.113.7");
        request.addHeader(TrustedEdgeRequestFilter.FORWARDED_PROTO_HEADER, "https");

        HttpServletRequest resolved = run(proxyEdge, request);

        assertThat(resolved.getRemoteAddr()).isEqualTo("203.0.113.7");
        assertThat(resolved.getScheme()).isEqualTo("https");
    }

    @Test
    void trustedProxy_usesRightmostForwardedFor() throws Exception {
        MockHttpServletRequest request = proxyRequest();
        request.addHeader(TrustedEdgeRequestFilter.FORWARDED_FOR_HEADER, " 203.0.113.7\t");

        assertThat(run(proxyEdge, request).getRemoteAddr()).isEqualTo("203.0.113.7");
    }

    /**
     * 위조 방어의 핵심 — 클라이언트가 선행 주입한 값은 ALB가 append한 값 왼쪽에 쌓이므로 채택되지 않는다.
     */
    @Test
    void trustedProxy_ignoresClientForgedForwardedForPrefix() throws Exception {
        MockHttpServletRequest request = proxyRequest();
        request.addHeader(TrustedEdgeRequestFilter.FORWARDED_FOR_HEADER, "1.2.3.4, 198.51.100.9, 203.0.113.7");

        assertThat(run(proxyEdge, request).getRemoteAddr())
                .isEqualTo("203.0.113.7")
                .isNotEqualTo("1.2.3.4");
    }

    /** 클라이언트가 별도 header line으로 주입해도 마지막 line(= ALB가 붙인 값)만 본다. */
    @Test
    void trustedProxy_ignoresClientForgedForwardedForHeaderLine() throws Exception {
        MockHttpServletRequest request = proxyRequest();
        request.addHeader(TrustedEdgeRequestFilter.FORWARDED_FOR_HEADER, "1.2.3.4");
        request.addHeader(TrustedEdgeRequestFilter.FORWARDED_FOR_HEADER, "203.0.113.7");

        assertThat(run(proxyEdge, request).getRemoteAddr())
                .isEqualTo("203.0.113.7")
                .isNotEqualTo("1.2.3.4");
    }

    /** ALB는 임의 이름의 custom header를 덮어쓰지 못하므로 이 엣지에서 Laimory-Client-IP는 값이 없다. */
    @Test
    void trustedProxy_ignoresCustomClientIpHeader() throws Exception {
        MockHttpServletRequest request = proxyRequest();
        request.addHeader(TrustedEdgeRequestFilter.CLIENT_IP_HEADER, "1.2.3.4");
        request.addHeader(TrustedEdgeRequestFilter.FORWARDED_FOR_HEADER, "203.0.113.7");

        assertThat(run(proxyEdge, request).getRemoteAddr()).isEqualTo("203.0.113.7");
    }

    @Test
    void trustedProxy_withoutForwardedFor_ignoresCustomClientIpHeader() throws Exception {
        MockHttpServletRequest request = proxyRequest();
        request.addHeader(TrustedEdgeRequestFilter.CLIENT_IP_HEADER, "1.2.3.4");

        assertThat(run(proxyEdge, request).getRemoteAddr()).isEqualTo(PROXY_PEER);
    }

    @ParameterizedTest
    @MethodSource("invalidForwardedForHeaders")
    void trustedProxy_malformedRightmostForwardedFor_fallsBackToSocketPeer(List<String> values) throws Exception {
        MockHttpServletRequest request = proxyRequest();
        values.forEach(value -> request.addHeader(TrustedEdgeRequestFilter.FORWARDED_FOR_HEADER, value));

        assertThat(run(proxyEdge, request).getRemoteAddr()).isEqualTo(PROXY_PEER);
    }

    @Test
    void trustedProxy_normalizesIpv6ForwardedFor() throws Exception {
        MockHttpServletRequest request = proxyRequest();
        request.addHeader(TrustedEdgeRequestFilter.FORWARDED_FOR_HEADER, "1.2.3.4, 2001:0DB8::1");

        assertThat(run(proxyEdge, request).getRemoteAddr()).isEqualTo("2001:db8::1");
    }

    @ParameterizedTest
    @MethodSource("validProtocols")
    void trustedProxy_singleProtocol_overridesRequestView(
            String header, String scheme, boolean secure, int port, String expectedUrl) throws Exception {
        MockHttpServletRequest request = proxyRequest();
        request.setServerName("external.example");
        request.setRequestURI("/oauth2/authorization/google");
        request.addHeader(TrustedEdgeRequestFilter.FORWARDED_PROTO_HEADER, header);

        HttpServletRequest resolved = run(proxyEdge, request);

        assertThat(resolved.getScheme()).isEqualTo(scheme);
        assertThat(resolved.isSecure()).isEqualTo(secure);
        assertThat(resolved.getServerPort()).isEqualTo(port);
        assertThat(resolved.getRequestURL().toString()).isEqualTo(expectedUrl);
    }

    @ParameterizedTest
    @MethodSource("proxyCidrBoundaries")
    void proxyCidrMatchesOnlyConfiguredRanges(String peer, boolean trusted) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/probe");
        request.setRemoteAddr(peer);
        request.addHeader(TrustedEdgeRequestFilter.FORWARDED_FOR_HEADER, "203.0.113.7");

        assertThat(run(proxyEdge, request).getRemoteAddr()).isEqualTo(trusted ? "203.0.113.7" : peer);
    }

    @Test
    void proxyCidrSupportsIpv6Ranges() throws Exception {
        TrustedEdgeRequestFilter filter = new TrustedEdgeRequestFilter(List.of("2001:db8::/32"));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/probe");
        request.setRemoteAddr("2001:db8:0:1::5");
        request.addHeader(TrustedEdgeRequestFilter.FORWARDED_FOR_HEADER, "203.0.113.7");

        assertThat(run(filter, request).getRemoteAddr()).isEqualTo("203.0.113.7");
    }

    /** dual-stack socket이 IPv4 peer를 IPv4-mapped IPv6로 보고해도 같은 IPv4 대역으로 판정한다. */
    @Test
    void ipv4MappedPeer_matchesIpv4Cidr() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/probe");
        request.setRemoteAddr("::ffff:10.0.16.20");
        request.addHeader(TrustedEdgeRequestFilter.FORWARDED_FOR_HEADER, "203.0.113.7");

        assertThat(run(proxyEdge, request).getRemoteAddr()).isEqualTo("203.0.113.7");
    }

    @Test
    void blankCidrEntriesAreIgnored() throws Exception {
        TrustedEdgeRequestFilter filter = new TrustedEdgeRequestFilter(List.of(" ", ""));
        MockHttpServletRequest request = proxyRequest();
        request.addHeader(TrustedEdgeRequestFilter.FORWARDED_FOR_HEADER, "203.0.113.7");

        assertThat(run(filter, request).getRemoteAddr()).isEqualTo(PROXY_PEER);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "10.0.0.0",
            "10.0.0.0/",
            "10.0.0.0/33",
            "10.0.0.0/-1",
            "10.0.0.0/2a",
            "10.0.0.0/020/8",
            "256.0.0.0/8",
            "example.com/24",
            "2001:db8::/129"})
    void malformedProxyCidr_failsFast(String cidr) {
        assertThatThrownBy(() -> new TrustedEdgeRequestFilter(List.of(cidr)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app.edge.trusted-proxy-cidrs");
    }

    @ParameterizedTest
    @MethodSource("untrustedPeers")
    void untrustedPeer_ignoresAllForwardedHeaders(String peer) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/probe");
        request.setRemoteAddr(peer);
        request.setScheme("http");
        request.setServerPort(8080);
        request.addHeader(TrustedEdgeRequestFilter.CLIENT_IP_HEADER, "203.0.113.7");
        request.addHeader(TrustedEdgeRequestFilter.FORWARDED_FOR_HEADER, "198.51.100.9");
        request.addHeader(TrustedEdgeRequestFilter.FORWARDED_PROTO_HEADER, "https");

        HttpServletRequest resolved = run(proxyEdge, request);

        assertThat(resolved.getRemoteAddr()).isEqualTo(peer);
        assertThat(resolved.getScheme()).isEqualTo("http");
        assertThat(resolved.isSecure()).isFalse();
        assertThat(resolved.getServerPort()).isEqualTo(8080);
    }

    @ParameterizedTest
    @MethodSource("validProtocols")
    void trustedSingleProtocol_overridesRequestView(
            String header, String scheme, boolean secure, int port, String expectedUrl) throws Exception {
        MockHttpServletRequest request = trustedRequest();
        request.setServerName("external.example");
        request.setRequestURI("/oauth2/authorization/google");
        request.addHeader(TrustedEdgeRequestFilter.FORWARDED_PROTO_HEADER, header);

        HttpServletRequest resolved = run(loopbackEdge, request);

        assertThat(resolved.getScheme()).isEqualTo(scheme);
        assertThat(resolved.isSecure()).isEqualTo(secure);
        assertThat(resolved.getServerPort()).isEqualTo(port);
        assertThat(resolved.getRequestURL().toString()).isEqualTo(expectedUrl);
    }

    @ParameterizedTest
    @MethodSource("invalidProtocolHeaders")
    void invalidProtocol_keepsRawRequestView(List<String> values) throws Exception {
        MockHttpServletRequest request = trustedRequest();
        values.forEach(value -> request.addHeader(TrustedEdgeRequestFilter.FORWARDED_PROTO_HEADER, value));

        HttpServletRequest resolved = run(loopbackEdge, request);

        assertThat(resolved.getScheme()).isEqualTo("http");
        assertThat(resolved.isSecure()).isFalse();
        assertThat(resolved.getServerPort()).isEqualTo(8080);
    }

    private HttpServletRequest run(TrustedEdgeRequestFilter filter, MockHttpServletRequest request) throws Exception {
        AtomicReference<HttpServletRequest> downstream = new AtomicReference<>();
        filter.doFilter(request, new MockHttpServletResponse(),
                (servletRequest, servletResponse) -> downstream.set((HttpServletRequest) servletRequest));
        return downstream.get();
    }

    private static MockHttpServletRequest trustedRequest() {
        return rawRequest(TrustedEdgeRequestFilter.TRUSTED_SOCKET_PEER);
    }

    private static MockHttpServletRequest proxyRequest() {
        return rawRequest(PROXY_PEER);
    }

    private static MockHttpServletRequest rawRequest(String peer) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/probe");
        request.setRemoteAddr(peer);
        request.setScheme("http");
        request.setSecure(false);
        request.setServerPort(8080);
        return request;
    }

    private static Stream<Arguments> invalidIpHeaders() {
        return Stream.of(
                Arguments.of(List.of()),
                Arguments.of(List.of("")),
                Arguments.of(List.of(" \t ")),
                Arguments.of(List.of("203.0.113.7, 198.51.100.9")),
                Arguments.of(List.of("example.com")),
                Arguments.of(List.of("203.0.113.7/32")),
                Arguments.of(List.of("203.0.113.7:443")),
                Arguments.of(List.of("[2001:db8::1]")),
                Arguments.of(List.of("2001:db8::1%eth0")),
                Arguments.of(List.of("203.0.113.007")),
                Arguments.of(List.of("256.0.0.1")),
                Arguments.of(List.of("2001:db8::1::2")),
                Arguments.of(List.of("2001:db8::g")),
                Arguments.of(List.of("2001:db8:: 1")),
                // group 수 경계: 압축 없는 7-group/9-group, ::가 채울 group이 없는 8-group+압축은 전부 무효다.
                Arguments.of(List.of("1:2:3:4:5:6:7")),
                Arguments.of(List.of("1:2:3:4:5:6:7:8:9")),
                Arguments.of(List.of("1:2:3:4:5:6:7:8::")));
    }

    private static Stream<Arguments> invalidForwardedForHeaders() {
        return Stream.of(
                Arguments.of(List.of()),
                Arguments.of(List.of("")),
                Arguments.of(List.of(" \t ")),
                // 최우측이 무효면 왼쪽의 유효한 값으로 되돌아가지 않는다(되돌아가면 위조 값이 채택된다).
                Arguments.of(List.of("203.0.113.7, unknown")),
                Arguments.of(List.of("203.0.113.7,")),
                Arguments.of(List.of("203.0.113.7", "")),
                Arguments.of(List.of("[2001:db8::1]")),
                Arguments.of(List.of("203.0.113.7:443")),
                Arguments.of(List.of("256.0.0.1")));
    }

    private static Stream<Arguments> proxyCidrBoundaries() {
        return Stream.of(
                Arguments.of("10.0.0.0", true),
                Arguments.of("10.0.15.255", true),
                Arguments.of("10.0.16.0", true),
                Arguments.of("10.0.31.255", true),
                Arguments.of("10.0.32.0", false),
                Arguments.of("10.0.68.10", false),
                Arguments.of("9.255.255.255", false),
                Arguments.of("10.1.0.1", false));
    }

    private static Stream<Arguments> canonicalIpv6() {
        return Stream.of(
                // full 8-group 비압축(대문자·leading zero) 입력 → 최장 zero-run 압축 + 소문자(RFC 5952 출력).
                Arguments.of("2001:0DB8:0000:0000:0000:FF00:0042:8329", "2001:db8::ff00:42:8329"),
                // 단일 zero group은 ::로 압축하지 않는다(RFC 5952 §4.2.2).
                Arguments.of("2001:db8:0:1:2:3:4:5", "2001:db8:0:1:2:3:4:5"),
                // 길이가 같은 zero-run이 여럿이면 첫 run을 압축한다(RFC 5952 §4.2.3).
                Arguments.of("2001:0:0:1:0:0:1:1", "2001::1:0:0:1:1"));
    }

    private static Stream<String> untrustedPeers() {
        return Stream.of("10.0.32.10", "172.16.0.10", "192.168.0.10", "::1");
    }

    private static Stream<Arguments> validProtocols() {
        return Stream.of(
                Arguments.of("https", "https", true, 443,
                        "https://external.example/oauth2/authorization/google"),
                Arguments.of("http", "http", false, 80,
                        "http://external.example/oauth2/authorization/google"));
    }

    private static Stream<Arguments> invalidProtocolHeaders() {
        return Stream.of(
                Arguments.of(List.of()),
                Arguments.of(List.of("")),
                Arguments.of(List.of("HTTPS")),
                Arguments.of(List.of("https,http")),
                Arguments.of(List.of("https", "https")));
    }
}
