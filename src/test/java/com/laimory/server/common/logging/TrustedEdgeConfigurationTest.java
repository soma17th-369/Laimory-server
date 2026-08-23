package com.laimory.server.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.OrderUtils;

/** 운영 설정과 servlet filter 순서가 trusted-edge 경계를 우회하지 못하도록 고정한다. */
class TrustedEdgeConfigurationTest {

    @Test
    void nativeForwardedHeaderSupport_isDisabled() throws IOException {
        Properties properties = deployedDefaults();

        assertThat(properties.getProperty("server.forward-headers-strategy")).isEqualTo("none");
        String legacyRemoteIpPrefix = String.join(".", "server", "tomcat", "remoteip") + ".";
        assertThat(properties.stringPropertyNames())
                .noneMatch(name -> name.startsWith(legacyRemoteIpPrefix));
    }

    /**
     * checked-in 기본값은 비어 있어야 한다 — ALB가 없는 환경(현행 dev·로컬)이 사설망 peer의
     * X-Forwarded-For를 신뢰하지 않게 하고, 실제 대역은 배포 환경 {@code .env}가 소유한다.
     */
    @Test
    void trustedProxyCidrs_defaultToEmptyAndAreEnvironmentOwned() throws IOException {
        assertThat(deployedDefaults().getProperty("app.edge.trusted-proxy-cidrs"))
                .isEqualTo("${APP_EDGE_TRUSTED_PROXY_CIDRS:}");
    }

    private static Properties deployedDefaults() throws IOException {
        Properties properties = new Properties();
        try (InputStream input = TrustedEdgeConfigurationTest.class
                .getResourceAsStream("/application.properties")) {
            assertThat(input).isNotNull();
            properties.load(input);
        }
        return properties;
    }

    @Test
    void trustedEdgeRunsImmediatelyBeforeTransactionLogging() {
        assertThat(OrderUtils.getOrder(TrustedEdgeRequestFilter.class))
                .isEqualTo(Ordered.HIGHEST_PRECEDENCE);
        assertThat(OrderUtils.getOrder(TransactionIdFilter.class))
                .isEqualTo(Ordered.HIGHEST_PRECEDENCE + 1);
    }
}
