package com.laimory.server.common.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Enumeration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 같은 호스트의 nginx가 덮어쓴 client IP와 protocol만 신뢰하는 request 경계.
 *
 * <p>원본 socket peer가 정확히 {@code 127.0.0.1}일 때만 단일 {@code Laimory-Client-IP}와
 * {@code X-Forwarded-Proto}를 해석한다. 그 밖의 peer와 malformed/multi-value header는 원본 request
 * view로 수렴하며 header 원문은 기록하지 않는다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TrustedEdgeRequestFilter extends OncePerRequestFilter {

    static final String CLIENT_IP_HEADER = "Laimory-Client-IP";
    static final String FORWARDED_PROTO_HEADER = "X-Forwarded-Proto";
    static final String TRUSTED_SOCKET_PEER = "127.0.0.1";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String socketPeer = request.getRemoteAddr();
        boolean trustedPeer = TRUSTED_SOCKET_PEER.equals(socketPeer);

        String clientIp = trustedPeer ? resolveClientIp(request, socketPeer) : socketPeer;
        ForwardedProtocol protocol = trustedPeer ? resolveProtocol(request) : null;

        chain.doFilter(new TrustedEdgeRequestWrapper(request, clientIp, protocol), response);
    }

    private static String resolveClientIp(HttpServletRequest request, String socketPeer) {
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
