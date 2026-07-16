package com.laimory.server.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** 운영 설정의 forwarded-header 신뢰 경계를 실제 properties 파싱 결과로 고정한다. */
class RemoteIpValveConfigurationTest {

    private static final String INTERNAL_PROXIES = "server.tomcat.remoteip.internal-proxies";

    @Test
    void internalProxyTrust_isLimitedToLoopback() throws IOException {
        Properties properties = new Properties();
        try (InputStream input = getClass().getResourceAsStream("/application.properties")) {
            assertThat(input).isNotNull();
            properties.load(input);
        }

        String configured = properties.getProperty(INTERNAL_PROXIES);
        assertThat(configured).isNotBlank();
        Pattern trusted = Pattern.compile(configured);
        assertThat(trusted.matcher("127.0.0.1").matches()).isTrue();
        assertThat(trusted.matcher("0:0:0:0:0:0:0:1").matches()).isTrue();
        assertThat(trusted.matcher("::1").matches()).isTrue();
        assertThat(trusted.matcher("10.0.0.10").matches()).isFalse();
        assertThat(trusted.matcher("172.16.0.10").matches()).isFalse();
        assertThat(trusted.matcher("192.168.0.10").matches()).isFalse();
    }
}
