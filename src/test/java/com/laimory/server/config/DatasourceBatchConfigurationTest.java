package com.laimory.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class DatasourceBatchConfigurationTest {

    @Test
    void defaultAndDockerProfilesEnableMySqlBatchRewrite() throws IOException {
        assertBatchRewriteEnabled("application.properties");
        assertBatchRewriteEnabled("application-docker.properties");
    }

    private void assertBatchRewriteEnabled(String resourceName) throws IOException {
        Properties properties = new Properties();
        try (InputStream input = Objects.requireNonNull(
                getClass().getClassLoader().getResourceAsStream(resourceName), resourceName)) {
            properties.load(input);
        }

        assertThat(properties.getProperty("spring.datasource.url"))
                .as(resourceName)
                .endsWith("&rewriteBatchedStatements=true");
    }
}
