package com.laimory.server.admin;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.apache.catalina.connector.Connector;
import org.apache.coyote.http11.Http11NioProtocol;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.core.env.Environment;

/** 관리자 connector의 설정과 실제 bound port. 설정값 0을 요청 허용 포트로 사용하지 않는다. */
public final class AdminServer {

    private final Integer configuredPort;
    private final String environment;
    private volatile Connector connector;

    public AdminServer(Environment properties) {
        environment = properties.getProperty("APP_ENV", "local");
        String rawPort = properties.getProperty("APP_ADMIN_PORT");
        if (rawPort == null) {
            configuredPort = null;
            return;
        }
        if (!rawPort.matches("[0-9]{1,5}")) {
            throw new IllegalArgumentException("APP_ADMIN_PORT must be a valid port");
        }
        configuredPort = Integer.parseInt(rawPort);
        if (configuredPort > 65535 || "test".equals(environment)) {
            throw new IllegalArgumentException("APP_ADMIN_PORT is invalid for this environment");
        }
        if (("dev".equals(environment) || "prod".equals(environment)) && configuredPort != 8081) {
            throw new IllegalArgumentException("APP_ADMIN_PORT must be 8081 in dev/prod");
        }
    }

    public void customize(TomcatServletWebServerFactory factory) {
        if (configuredPort == null) {
            return;
        }
        Connector adminConnector = new Connector(Http11NioProtocol.class.getName());
        adminConnector.setPort(configuredPort);
        Http11NioProtocol protocol = (Http11NioProtocol) adminConnector.getProtocolHandler();
        try {
            protocol.setAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}));
        } catch (UnknownHostException impossible) {
            throw new IllegalStateException(impossible);
        }
        protocol.setMinSpareThreads(2);
        protocol.setMaxThreads(3);
        protocol.setConnectionTimeout(20_000);
        factory.addAdditionalTomcatConnectors(adminConnector);
        connector = adminConnector;
    }

    public int boundPort() {
        Connector current = connector;
        return current == null ? -1 : current.getLocalPort();
    }

    public String environment() {
        return environment;
    }
}
