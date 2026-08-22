package com.laimory.server.common.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Enumeration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 신뢰하는 엣지가 넘긴 client IP와 protocol만 해석하는 request 경계.
 *
 * <p>엣지가 둘인 전환기라 socket peer로 계약을 나눈다.
 *
 * <ul>
 *   <li><b>ALB 엣지</b> — peer가 {@code app.edge.trusted-proxy-cidrs}(ALB ENI가 사는 서브넷) 안이면
 *       {@code X-Forwarded-For} <b>최우측</b> 값을 client IP로 쓴다. ALB는 자신이 관찰한 TCP peer를
 *       XFF 오른쪽에 append하므로 클라이언트가 미리 넣은 위조 값은 전부 왼쪽에 쌓인다(최좌측을 쓰면
 *       위조가 그대로 통과한다). ALB는 임의 이름의 custom header를 덮어쓰지 못하므로 이 엣지에서는
 *       {@code Laimory-Client-IP}를 신뢰하지 않는다. 전제는 "ALB 앞에 다른 프록시가 없다"이며,
 *       API 앞에 CDN을 붙이면 이 전제가 깨진다.</li>
 *   <li><b>loopback nginx 엣지</b>(전환기 한정) — peer가 정확히 {@code 127.0.0.1}이면 같은 호스트의
 *       nginx가 덮어쓴 단일 {@code Laimory-Client-IP}를 client IP로 쓴다. nginx를 호스트에서 제거한
 *       뒤 이 분기와 header 상수는 별도 작업으로 삭제한다(#327 후속).</li>
 * </ul>
 *
 * <p>두 엣지 모두 단일 {@code X-Forwarded-Proto}만 scheme/secure/serverPort view로 반영한다. 그 밖의
 * peer와 malformed/multi-value header는 원본 request view로 수렴하며 header 원문은 기록하지 않는다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TrustedEdgeRequestFilter extends OncePerRequestFilter {

    static final String CLIENT_IP_HEADER = "Laimory-Client-IP";
    static final String FORWARDED_FOR_HEADER = "X-Forwarded-For";
    static final String FORWARDED_PROTO_HEADER = "X-Forwarded-Proto";
    static final String TRUSTED_SOCKET_PEER = "127.0.0.1";

    private final List<CidrBlock> trustedProxyCidrs;

    /**
     * @param trustedProxyCidrs ALB ENI가 사는 서브넷 CIDR 목록. 배포 환경 {@code .env}가 소유하며
     *     checked-in 기본값은 비어 있다(= ALB 엣지 없음). 형식이 잘못되면 기동 실패한다.
     */
    public TrustedEdgeRequestFilter(@Value("${app.edge.trusted-proxy-cidrs:}") List<String> trustedProxyCidrs) {
        this.trustedProxyCidrs = trustedProxyCidrs.stream()
                .filter(cidr -> !cidr.isBlank())
                .map(CidrBlock::parse)
                .toList();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String socketPeer = request.getRemoteAddr();
        TrustedEdge edge = classify(socketPeer);

        String clientIp = switch (edge) {
            case PROXY -> resolveForwardedForClientIp(request, socketPeer);
            case LOOPBACK_NGINX -> resolveCustomHeaderClientIp(request, socketPeer);
            case NONE -> socketPeer;
        };
        ForwardedProtocol protocol = edge != TrustedEdge.NONE ? resolveProtocol(request) : null;

        chain.doFilter(new TrustedEdgeRequestWrapper(request, clientIp, protocol), response);
    }

    /**
     * 설정으로 명시한 ALB 엣지를 먼저 본다. 운영에서 두 집합(loopback과 ALB 서브넷)은 서로소라 순서가
     * 결과를 바꾸지 않지만, 명시 설정이 하드코딩된 전환기 분기보다 우선한다는 규칙을 고정한다.
     */
    private TrustedEdge classify(String socketPeer) {
        if (matchesTrustedProxy(socketPeer)) {
            return TrustedEdge.PROXY;
        }
        // nginx 제거 후 삭제 예정 — 그전까지 dev는 client → nginx:443 → 127.0.0.1:8080으로 들어온다.
        if (TRUSTED_SOCKET_PEER.equals(socketPeer)) {
            return TrustedEdge.LOOPBACK_NGINX;
        }
        return TrustedEdge.NONE;
    }

    private boolean matchesTrustedProxy(String socketPeer) {
        if (trustedProxyCidrs.isEmpty()) {
            return false;
        }
        byte[] peer = addressBytes(socketPeer);
        return peer != null && trustedProxyCidrs.stream().anyMatch(cidr -> cidr.contains(peer));
    }

    /**
     * ALB가 append한 최우측 값만 신뢰한다. 유효한 IP literal이 아니면 왼쪽으로 되돌아가지 않고
     * socket peer로 수렴한다 — 되돌아가면 클라이언트가 심은 값이 채택된다.
     */
    private static String resolveForwardedForClientIp(HttpServletRequest request, String socketPeer) {
        String normalized = IpLiteralNormalizer.normalize(rightmostForwardedFor(request));
        return normalized != null ? normalized : socketPeer;
    }

    private static String rightmostForwardedFor(HttpServletRequest request) {
        Enumeration<String> values = request.getHeaders(FORWARDED_FOR_HEADER);
        if (values == null) {
            return null;
        }

        String lastValue = null;
        while (values.hasMoreElements()) {
            // 같은 이름의 header line은 순서가 보존되므로 마지막 line이 chain의 오른쪽 끝이다.
            lastValue = values.nextElement();
        }
        if (lastValue == null) {
            return null;
        }
        return lastValue.substring(lastValue.lastIndexOf(',') + 1);
    }

    private static String resolveCustomHeaderClientIp(HttpServletRequest request, String socketPeer) {
        String header = singleHeaderValue(request, CLIENT_IP_HEADER);
        String normalized = IpLiteralNormalizer.normalize(header);
        return normalized != null ? normalized : socketPeer;
    }

    private static ForwardedProtocol resolveProtocol(HttpServletRequest request) {
        String value = singleHeaderValue(request, FORWARDED_PROTO_HEADER);
        if ("https".equals(value)) {
            return ForwardedProtocol.HTTPS;
        }
        if ("http".equals(value)) {
            return ForwardedProtocol.HTTP;
        }
        return null;
    }

    private static String singleHeaderValue(HttpServletRequest request, String name) {
        Enumeration<String> values = request.getHeaders(name);
        if (values == null || !values.hasMoreElements()) {
            return null;
        }

        String value = values.nextElement();
        if (values.hasMoreElements()) {
            return null;
        }
        return trimOptionalWhitespace(value);
    }

    private static String trimOptionalWhitespace(String value) {
        if (value == null) {
            return null;
        }
        int start = 0;
        int end = value.length();
        while (start < end && isOptionalWhitespace(value.charAt(start))) {
            start++;
        }
        while (end > start && isOptionalWhitespace(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(start, end);
    }

    private static boolean isOptionalWhitespace(char value) {
        return value == ' ' || value == '\t';
    }

    /** DNS 조회 없이 IP literal만 byte 표현으로 바꾼다. IPv4-mapped IPv6는 4 byte IPv4가 된다. */
    private static byte[] addressBytes(String literal) {
        String normalized = IpLiteralNormalizer.normalize(literal);
        if (normalized == null) {
            return null;
        }
        try {
            return InetAddress.getByName(normalized).getAddress();
        } catch (UnknownHostException neverForLiteral) {
            return null;
        }
    }

    private enum TrustedEdge {
        PROXY,
        LOOPBACK_NGINX,
        NONE
    }

    /** 신뢰 proxy 대역. address family가 같고 prefix bit가 일치할 때만 포함으로 본다. */
    private record CidrBlock(byte[] network, int prefixLength) {

        static CidrBlock parse(String value) {
            String cidr = value.trim();
            int slash = cidr.indexOf('/');
            if (slash < 0) {
                throw new IllegalArgumentException(invalid(value));
            }

            byte[] network = addressBytes(cidr.substring(0, slash));
            Integer prefixLength = parsePrefixLength(cidr.substring(slash + 1));
            if (network == null || prefixLength == null || prefixLength > network.length * 8) {
                throw new IllegalArgumentException(invalid(value));
            }
            return new CidrBlock(network, prefixLength);
        }

        boolean contains(byte[] address) {
            if (address.length != network.length) {
                return false;
            }

            int fullBytes = prefixLength / 8;
            for (int index = 0; index < fullBytes; index++) {
                if (address[index] != network[index]) {
                    return false;
                }
            }
            int remainingBits = prefixLength % 8;
            if (remainingBits == 0) {
                return true;
            }
            int mask = 0xFF << (8 - remainingBits);
            return (address[fullBytes] & mask) == (network[fullBytes] & mask);
        }

        private static Integer parsePrefixLength(String value) {
            if (value.isEmpty() || value.length() > 3) {
                return null;
            }
            int parsed = 0;
            for (int index = 0; index < value.length(); index++) {
                char character = value.charAt(index);
                if (character < '0' || character > '9') {
                    return null;
                }
                parsed = parsed * 10 + character - '0';
            }
            return parsed;
        }

        private static String invalid(String value) {
            return "app.edge.trusted-proxy-cidrs must contain IPv4/IPv6 CIDR blocks (e.g. 10.0.0.0/20): " + value;
        }
    }

    private enum ForwardedProtocol {
        HTTP("http", false, 80),
        HTTPS("https", true, 443);

        private final String scheme;
        private final boolean secure;
        private final int port;

        ForwardedProtocol(String scheme, boolean secure, int port) {
            this.scheme = scheme;
            this.secure = secure;
            this.port = port;
        }
    }

    private static final class TrustedEdgeRequestWrapper extends HttpServletRequestWrapper {

        private final String clientIp;
        private final ForwardedProtocol protocol;

        private TrustedEdgeRequestWrapper(HttpServletRequest request, String clientIp, ForwardedProtocol protocol) {
            super(request);
            this.clientIp = clientIp;
            this.protocol = protocol;
        }

        @Override
        public String getRemoteAddr() {
            return clientIp;
        }

        @Override
        public String getScheme() {
            return protocol != null ? protocol.scheme : super.getScheme();
        }

        @Override
        public boolean isSecure() {
            return protocol != null ? protocol.secure : super.isSecure();
        }

        @Override
        public int getServerPort() {
            return protocol != null ? protocol.port : super.getServerPort();
        }

        @Override
        public StringBuffer getRequestURL() {
            String scheme = getScheme();
            String serverName = getServerName();
            StringBuffer url = new StringBuffer()
                    .append(scheme)
                    .append("://")
                    .append(serverName.indexOf(':') >= 0 ? "[" + serverName + "]" : serverName);
            int port = getServerPort();
            if (!("http".equals(scheme) && port == 80)
                    && !("https".equals(scheme) && port == 443)) {
                url.append(':').append(port);
            }
            return url.append(getRequestURI());
        }
    }
}
