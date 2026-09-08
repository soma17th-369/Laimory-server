package com.laimory.server.admin;

import static org.assertj.core.api.Assertions.*;

import java.net.InetAddress;
import org.apache.coyote.http11.Http11NioProtocol;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.mock.env.MockEnvironment;

class AdminServerTest {
    @Test
    void absentPortIsDisabled() {
        AdminServer server = new AdminServer(new MockEnvironment());
        TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory();
        server.customize(factory);
        assertThat(factory.getAdditionalTomcatConnectors()).isEmpty();
        assertThat(server.boundPort()).isNegative();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "-1", "65536", "false", "8081x"})
    void invalidPortFailsStartup(String port) {
        assertThatThrownBy(() -> new AdminServer(new MockEnvironment().withProperty("APP_ADMIN_PORT", port)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deploymentEnvironmentsArePinned() {
        for (String env : new String[] {"dev", "prod", "test"}) {
            assertThatThrownBy(() -> new AdminServer(new MockEnvironment().withProperty("APP_ENV", env)
                    .withProperty("APP_ADMIN_PORT", "8082"))).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new AdminServer(new MockEnvironment().withProperty("APP_ENV", "test")
                .withProperty("APP_ADMIN_PORT", "8081"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void connectorUsesLoopbackAndSmallDedicatedThreadPool() throws Exception {
        AdminServer server = new AdminServer(new MockEnvironment().withProperty("APP_ENV", "prod").withProperty("APP_ADMIN_PORT", "8081"));
        TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory();
        server.customize(factory);
        var connector = factory.getAdditionalTomcatConnectors().getFirst();
        var protocol = (Http11NioProtocol) connector.getProtocolHandler();
        assertThat(protocol.getAddress()).isEqualTo(InetAddress.getByName("127.0.0.1"));
        assertThat(protocol.getMaxThreads()).isEqualTo(3);
        assertThat(protocol.getMinSpareThreads()).isEqualTo(2);
        assertThat(connector.getPort()).isEqualTo(8081);
    }
}
