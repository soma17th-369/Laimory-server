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
        Properties properties = new Properties();
        try (InputStream input = getClass().getResourceAsStream("/application.properties")) {
            assertThat(input).isNotNull();
            properties.load(input);
        }

        assertThat(properties.getProperty("server.forward-headers-strategy")).isEqualTo("none");
        String legacyRemoteIpPrefix = String.join(".", "server", "tomcat", "remoteip") + ".";
        assertThat(properties.stringPropertyNames())
                .noneMatch(name -> name.startsWith(legacyRemoteIpPrefix));
    }

    @Test
    void trustedEdgeRunsImmediatelyBeforeTransactionLogging() {
        assertThat(OrderUtils.getOrder(TrustedEdgeRequestFilter.class))
                .isEqualTo(Ordered.HIGHEST_PRECEDENCE);
        assertThat(OrderUtils.getOrder(TransactionIdFilter.class))
                .isEqualTo(Ordered.HIGHEST_PRECEDENCE + 1);
    }
}
