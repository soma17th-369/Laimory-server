package com.laimory.server.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class TrustedEdgeRequestFilterTest {

    private final TrustedEdgeRequestFilter filter = new TrustedEdgeRequestFilter();

    @Test
    void trustedLoopback_usesSingleNormalizedIpv4_andIgnoresXffAndUserAgent() throws Exception {
        MockHttpServletRequest request = trustedRequest();
        request.addHeader(TrustedEdgeRequestFilter.CLIENT_IP_HEADER, " 203.0.113.7\t");
        request.addHeader("X-Forwarded-For", "198.51.100.9");
        request.addHeader("User-Agent", "203.0.113.99");

        HttpServletRequest resolved = run(request);

        assertThat(resolved.getRemoteAddr()).isEqualTo("203.0.113.7");
    }

    @Test
    void trustedLoopback_normalizesCompressedIpv6() throws Exception {
        MockHttpServletRequest request = trustedRequest();
        request.addHeader(TrustedEdgeRequestFilter.CLIENT_IP_HEADER, "2001:0DB8::1");

        assertThat(run(request).getRemoteAddr()).isEqualTo("2001:db8::1");
    }

    @Test
    void trustedLoopback_acceptsIpv4EmbeddedIpv6() throws Exception {
        MockHttpServletRequest request = trustedRequest();
        request.addHeader(TrustedEdgeRequestFilter.CLIENT_IP_HEADER, "::ffff:192.0.2.128");

        assertThat(run(request).getRemoteAddr()).isEqualTo("::ffff:c000:280");
    }

    @ParameterizedTest
    @MethodSource("canonicalIpv6")
    void trustedLoopback_canonicalizesValidIpv6GroupBoundaries(String header, String expected) throws Exception {
        MockHttpServletRequest request = trustedRequest();
        request.addHeader(TrustedEdgeRequestFilter.CLIENT_IP_HEADER, header);

        assertThat(run(request).getRemoteAddr()).isEqualTo(expected);
    }

    @ParameterizedTest
    @MethodSource("invalidIpHeaders")
    void malformedOrMissingClientIp_fallsBackToSocketPeer(List<String> values) throws Exception {
        MockHttpServletRequest request = trustedRequest();
        values.forEach(value -> request.addHeader(TrustedEdgeRequestFilter.CLIENT_IP_HEADER, value));

        assertThat(run(request).getRemoteAddr()).isEqualTo(TrustedEdgeRequestFilter.TRUSTED_SOCKET_PEER);
    }

    @Test
    void repeatedIdenticalClientIp_stillFallsBackToSocketPeer() throws Exception {
        MockHttpServletRequest request = trustedRequest();
        request.addHeader(TrustedEdgeRequestFilter.CLIENT_IP_HEADER, "203.0.113.7");
        request.addHeader(TrustedEdgeRequestFilter.CLIENT_IP_HEADER, "203.0.113.7");

        assertThat(run(request).getRemoteAddr()).isEqualTo(TrustedEdgeRequestFilter.TRUSTED_SOCKET_PEER);
    }

    @ParameterizedTest
    @MethodSource("untrustedPeers")
    void untrustedPeer_ignoresAllForwardedHeaders(String peer) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/probe");
        request.setRemoteAddr(peer);
        request.setScheme("http");
        request.setServerPort(8080);
        request.addHeader(TrustedEdgeRequestFilter.CLIENT_IP_HEADER, "203.0.113.7");
        request.addHeader("X-Forwarded-For", "198.51.100.9");
        request.addHeader(TrustedEdgeRequestFilter.FORWARDED_PROTO_HEADER, "https");

        HttpServletRequest resolved = run(request);

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

        HttpServletRequest resolved = run(request);

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

        HttpServletRequest resolved = run(request);

        assertThat(resolved.getScheme()).isEqualTo("http");
        assertThat(resolved.isSecure()).isFalse();
        assertThat(resolved.getServerPort()).isEqualTo(8080);
    }

    private HttpServletRequest run(MockHttpServletRequest request) throws Exception {
        AtomicReference<HttpServletRequest> downstream = new AtomicReference<>();
        filter.doFilter(request, new MockHttpServletResponse(),
                (servletRequest, servletResponse) -> downstream.set((HttpServletRequest) servletRequest));
        return downstream.get();
    }

    private static MockHttpServletRequest trustedRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/probe");
        request.setRemoteAddr(TrustedEdgeRequestFilter.TRUSTED_SOCKET_PEER);
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
        return Stream.of("10.0.16.10", "172.16.0.10", "192.168.0.10", "::1");
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
