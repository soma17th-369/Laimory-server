package com.laimory.server.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.laimory.server.appconfig.AppConfigRepository;
import com.laimory.server.terms.repository.TermDocumentInsertRepository;
import com.laimory.server.terms.service.TermDocumentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(classes = AdminHttpTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"APP_ENV=test", "management.server.port=0",
                "management.endpoint.health.group.readiness.include=readinessState"})
@DirtiesContext
class AdminDisabledHttpTest {
    @LocalServerPort int mainPort;
    @LocalManagementPort int managementPort;
    @Autowired AdminServer server;
    @MockitoBean TermDocumentService documents;
    @MockitoBean TermDocumentInsertRepository inserts;
    @MockitoBean AppConfigRepository configs;

    @Test
    void allAdminResourcesAreClosedWithoutPortProperty() throws Exception {
        assertThat(server.boundPort()).isNegative();
        for (int port : new int[] {mainPort, managementPort}) {
            for (String path : new String[] {"/admin", "/admin/", "/admin/admin.js", "/admin/admin.css", "/admin/api/csrf", "/admin/api/terms"}) {
                assertThat(AdminHttpTest.raw(port, path, "Host: localhost:8081")).as(path).startsWith("HTTP/1.1 404");
            }
        }
    }
}
